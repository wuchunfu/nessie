/*
 * Copyright (C) 2020 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.server.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.Content;
import org.projectnessie.model.DeltaLakeTable;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.IcebergView;
import org.projectnessie.model.ImmutableCommitMeta;
import org.projectnessie.model.ImmutableDeltaLakeTable;
import org.projectnessie.model.ImmutableIcebergTable;
import org.projectnessie.model.ImmutableIcebergView;
import org.projectnessie.model.ImmutableNamespace;
import org.projectnessie.model.Namespace;
import org.projectnessie.server.store.proto.ObjectTypes;
import org.projectnessie.versioned.ContentAttachment;
import org.projectnessie.versioned.ContentAttachmentKey;
import org.projectnessie.versioned.Serializer;
import org.projectnessie.versioned.StoreWorker;

public class TableCommitMetaStoreWorker implements StoreWorker<Content, CommitMeta, Content.Type> {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Serializer<CommitMeta> metaSerializer = new MetadataSerializer();

  @Override
  public ByteString toStoreOnReferenceState(
      Content content, Consumer<ContentAttachment> attachmentConsumer) {
    ObjectTypes.Content.Builder builder = ObjectTypes.Content.newBuilder().setId(content.getId());
    if (content instanceof IcebergTable) {
      toStoreIcebergTable((IcebergTable) content, builder);
    } else if (content instanceof IcebergView) {
      toStoreIcebergView((IcebergView) content, builder);
    } else if (content instanceof DeltaLakeTable) {
      toStoreDeltaLakeTable((DeltaLakeTable) content, builder);
    } else if (content instanceof Namespace) {
      toStoreNamespace((Namespace) content, builder);
    } else {
      throw new IllegalArgumentException("Unknown type " + content);
    }

    return builder.build().toByteString();
  }

  private static void toStoreDeltaLakeTable(
      DeltaLakeTable content, ObjectTypes.Content.Builder builder) {
    ObjectTypes.DeltaLakeTable.Builder table =
        ObjectTypes.DeltaLakeTable.newBuilder()
            .addAllMetadataLocationHistory(content.getMetadataLocationHistory())
            .addAllCheckpointLocationHistory(content.getCheckpointLocationHistory());
    String lastCheckpoint = content.getLastCheckpoint();
    if (lastCheckpoint != null) {
      table.setLastCheckpoint(lastCheckpoint);
    }
    builder.setDeltaLakeTable(table);
  }

  private static void toStoreNamespace(Namespace content, ObjectTypes.Content.Builder builder) {
    builder.setNamespace(
        ObjectTypes.Namespace.newBuilder()
            .addAllElements(content.getElements())
            .putAllProperties(content.getProperties())
            .build());
  }

  private static void toStoreIcebergView(IcebergView view, ObjectTypes.Content.Builder builder) {
    ObjectTypes.IcebergViewState.Builder stateBuilder =
        ObjectTypes.IcebergViewState.newBuilder()
            .setVersionId(view.getVersionId())
            .setSchemaId(view.getSchemaId())
            .setDialect(view.getDialect())
            .setSqlText(view.getSqlText())
            .setMetadataLocation(view.getMetadataLocation());

    builder.setIcebergViewState(stateBuilder);
  }

  private static void toStoreIcebergTable(IcebergTable table, ObjectTypes.Content.Builder builder) {
    ObjectTypes.IcebergRefState.Builder stateBuilder =
        ObjectTypes.IcebergRefState.newBuilder()
            .setSnapshotId(table.getSnapshotId())
            .setSchemaId(table.getSchemaId())
            .setSpecId(table.getSpecId())
            .setSortOrderId(table.getSortOrderId())
            .setMetadataLocation(table.getMetadataLocation());

    builder.setIcebergRefState(stateBuilder);
  }

  @Override
  public ByteString toStoreGlobalState(Content content) {
    ObjectTypes.Content.Builder builder = ObjectTypes.Content.newBuilder().setId(content.getId());
    if (content instanceof IcebergTable) {
      IcebergTable state = (IcebergTable) content;
      ObjectTypes.IcebergMetadataPointer.Builder stateBuilder =
          ObjectTypes.IcebergMetadataPointer.newBuilder()
              .setMetadataLocation(state.getMetadataLocation());
      builder.setIcebergMetadataPointer(stateBuilder);
    } else if (content instanceof IcebergView) {
      IcebergView state = (IcebergView) content;
      ObjectTypes.IcebergMetadataPointer.Builder stateBuilder =
          ObjectTypes.IcebergMetadataPointer.newBuilder()
              .setMetadataLocation(state.getMetadataLocation());
      builder.setIcebergMetadataPointer(stateBuilder);
    } else {
      throw new IllegalArgumentException("Unknown type " + content);
    }

    return builder.build().toByteString();
  }

  @Override
  public Content valueFromStore(
      ByteString onReferenceValue,
      Supplier<ByteString> globalState,
      Function<Stream<ContentAttachmentKey>, Stream<ContentAttachment>> attachmentsRetriever) {
    ObjectTypes.Content content = parse(onReferenceValue);
    Supplier<String> metadataPointerSupplier =
        () -> {
          ByteString global = globalState.get();
          if (global == null) {
            throw noIcebergMetadataPointer();
          }
          ObjectTypes.Content globalContent = parse(global);
          if (!globalContent.hasIcebergMetadataPointer()) {
            throw noIcebergMetadataPointer();
          }
          return globalContent.getIcebergMetadataPointer().getMetadataLocation();
        };
    switch (content.getObjectTypeCase()) {
      case DELTA_LAKE_TABLE:
        return valueFromStoreDeltaLakeTable(content);

      case ICEBERG_REF_STATE:
        return valueFromStoreIcebergTable(content, metadataPointerSupplier);

      case ICEBERG_VIEW_STATE:
        return valueFromStoreIcebergView(content, metadataPointerSupplier);

      case NAMESPACE:
        return valueFromStoreNamespace(content);

      case OBJECTTYPE_NOT_SET:
      default:
        throw new IllegalArgumentException("Unknown type " + content.getObjectTypeCase());
    }
  }

  private static ImmutableDeltaLakeTable valueFromStoreDeltaLakeTable(ObjectTypes.Content content) {
    ObjectTypes.DeltaLakeTable deltaLakeTable = content.getDeltaLakeTable();
    ImmutableDeltaLakeTable.Builder builder =
        ImmutableDeltaLakeTable.builder()
            .id(content.getId())
            .addAllMetadataLocationHistory(deltaLakeTable.getMetadataLocationHistoryList())
            .addAllCheckpointLocationHistory(deltaLakeTable.getCheckpointLocationHistoryList());
    if (deltaLakeTable.hasLastCheckpoint()) {
      builder.lastCheckpoint(content.getDeltaLakeTable().getLastCheckpoint());
    }
    return builder.build();
  }

  private static ImmutableNamespace valueFromStoreNamespace(ObjectTypes.Content content) {
    ObjectTypes.Namespace namespace = content.getNamespace();
    return ImmutableNamespace.builder()
        .id(content.getId())
        .elements(namespace.getElementsList())
        .putAllProperties(namespace.getPropertiesMap())
        .build();
  }

  private static ImmutableIcebergTable valueFromStoreIcebergTable(
      ObjectTypes.Content content, Supplier<String> metadataPointerSupplier) {
    ObjectTypes.IcebergRefState table = content.getIcebergRefState();
    String metadataLocation =
        table.hasMetadataLocation() ? table.getMetadataLocation() : metadataPointerSupplier.get();

    return IcebergTable.builder()
        .metadataLocation(metadataLocation)
        .snapshotId(table.getSnapshotId())
        .schemaId(table.getSchemaId())
        .specId(table.getSpecId())
        .sortOrderId(table.getSortOrderId())
        .id(content.getId())
        .build();
  }

  private static ImmutableIcebergView valueFromStoreIcebergView(
      ObjectTypes.Content content, Supplier<String> metadataPointerSupplier) {
    String metadataLocation;
    ObjectTypes.IcebergViewState view = content.getIcebergViewState();
    // If the (protobuf) view has the metadataLocation attribute set, use that one, otherwise
    // it's an old representation using global state.
    metadataLocation =
        view.hasMetadataLocation() ? view.getMetadataLocation() : metadataPointerSupplier.get();

    return IcebergView.builder()
        .metadataLocation(metadataLocation)
        .versionId(view.getVersionId())
        .schemaId(view.getSchemaId())
        .dialect(view.getDialect())
        .sqlText(view.getSqlText())
        .id(content.getId())
        .build();
  }

  private static IllegalArgumentException noIcebergMetadataPointer() {
    return new IllegalArgumentException(
        "Iceberg content from reference must have global state, but has none");
  }

  @Override
  public Content applyId(Content content, String id) {
    Objects.requireNonNull(content, "content must not be null");
    Preconditions.checkArgument(content.getId() == null, "content.getId() must be null");
    Objects.requireNonNull(id, "id must not be null");
    if (content instanceof IcebergTable) {
      return IcebergTable.builder().from(content).id(id).build();
    } else if (content instanceof DeltaLakeTable) {
      return ImmutableDeltaLakeTable.builder().from(content).id(id).build();
    } else if (content instanceof IcebergView) {
      return IcebergView.builder().from(content).id(id).build();
    } else if (content instanceof Namespace) {
      return ImmutableNamespace.builder().from(content).id(id).build();
    } else {
      throw new IllegalArgumentException("Unknown type " + content);
    }
  }

  @Override
  public String getId(Content content) {
    return content.getId();
  }

  @Override
  public Byte getPayload(Content content) {
    if (content instanceof IcebergTable) {
      return (byte) Content.Type.ICEBERG_TABLE.ordinal();
    } else if (content instanceof DeltaLakeTable) {
      return (byte) Content.Type.DELTA_LAKE_TABLE.ordinal();
    } else if (content instanceof IcebergView) {
      return (byte) Content.Type.ICEBERG_VIEW.ordinal();
    } else if (content instanceof Namespace) {
      return (byte) Content.Type.NAMESPACE.ordinal();
    } else {
      throw new IllegalArgumentException("Unknown type " + content);
    }
  }

  @Override
  public Content.Type getType(Content content) {
    return content.getType();
  }

  @Override
  public Content.Type getType(Byte payload) {
    if (payload == null || payload > Content.Type.values().length || payload < 0) {
      throw new IllegalArgumentException(
          String.format("Cannot create type from payload. Payload %d does not exist", payload));
    }
    return Content.Type.values()[payload];
  }

  @Override
  public Content.Type getType(ByteString onRefContent) {
    ObjectTypes.Content parsed = parse(onRefContent);

    if (parsed.hasIcebergRefState()) {
      return Content.Type.ICEBERG_TABLE;
    }
    if (parsed.hasIcebergViewState()) {
      return Content.Type.ICEBERG_VIEW;
    }

    if (parsed.hasDeltaLakeTable()) {
      return Content.Type.DELTA_LAKE_TABLE;
    }

    if (parsed.hasNamespace()) {
      return Content.Type.NAMESPACE;
    }

    throw new IllegalArgumentException("Unsupported on-ref content " + parsed);
  }

  @Override
  public boolean requiresGlobalState(Content content) {
    switch (content.getType()) {
      case ICEBERG_TABLE:
        // yes, Iceberg Tables used global state before, but no longer do so
      case ICEBERG_VIEW:
        // yes, Iceberg Views used global state before, but no longer do so
      case DELTA_LAKE_TABLE:
      case NAMESPACE:
      default:
        return false;
    }
  }

  @Override
  public boolean requiresGlobalState(ByteString content) {
    ObjectTypes.Content parsed = parse(content);
    switch (parsed.getObjectTypeCase()) {
      case ICEBERG_REF_STATE:
        return !parsed.getIcebergRefState().hasMetadataLocation();
      case ICEBERG_VIEW_STATE:
        return !parsed.getIcebergViewState().hasMetadataLocation();
      default:
        return false;
    }
  }

  private static ObjectTypes.Content parse(ByteString value) {
    try {
      return ObjectTypes.Content.parseFrom(value);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException("Failure parsing data", e);
    }
  }

  @Override
  public Serializer<CommitMeta> getMetadataSerializer() {
    return metaSerializer;
  }

  private static class MetadataSerializer implements Serializer<CommitMeta> {
    @Override
    public ByteString toBytes(CommitMeta value) {
      try {
        return ByteString.copyFrom(MAPPER.writeValueAsBytes(value));
      } catch (JsonProcessingException e) {
        throw new RuntimeException(String.format("Couldn't serialize commit meta %s", value), e);
      }
    }

    @Override
    public CommitMeta fromBytes(ByteString bytes) {
      try {
        return MAPPER.readValue(bytes.toByteArray(), CommitMeta.class);
      } catch (IOException e) {
        return ImmutableCommitMeta.builder()
            .message("unknown")
            .committer("unknown")
            .hash("unknown")
            .build();
      }
    }
  }

  @Override
  public boolean isNamespace(ByteString type) {
    try {
      return Content.Type.NAMESPACE == getType(type);
    } catch (Exception e) {
      return false;
    }
  }
}
