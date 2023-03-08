/*
 * Copyright (C) 2022 Dremio
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
package org.projectnessie.versioned.storage.cache;

import java.util.Set;
import javax.annotation.Nonnull;
import org.projectnessie.versioned.storage.common.config.StoreConfig;
import org.projectnessie.versioned.storage.common.exceptions.ObjNotFoundException;
import org.projectnessie.versioned.storage.common.exceptions.ObjTooLargeException;
import org.projectnessie.versioned.storage.common.exceptions.RefAlreadyExistsException;
import org.projectnessie.versioned.storage.common.exceptions.RefConditionFailedException;
import org.projectnessie.versioned.storage.common.exceptions.RefNotFoundException;
import org.projectnessie.versioned.storage.common.persist.CloseableIterator;
import org.projectnessie.versioned.storage.common.persist.Obj;
import org.projectnessie.versioned.storage.common.persist.ObjId;
import org.projectnessie.versioned.storage.common.persist.ObjType;
import org.projectnessie.versioned.storage.common.persist.Persist;
import org.projectnessie.versioned.storage.common.persist.Reference;

class CachingPersistImpl implements Persist {

  final Persist persist;
  final ObjCache cache;

  CachingPersistImpl(Persist persist, ObjCache cache) {
    this.persist = persist;
    this.cache = cache;
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public Obj fetchObj(@Nonnull @jakarta.annotation.Nonnull ObjId id) throws ObjNotFoundException {
    Obj o = cache.get(id);
    if (o != null) {
      return o;
    }
    try {
      o = persist.fetchObj(id);
      cache.put(o);
      return o;
    } catch (ObjNotFoundException e) {
      cache.remove(id);
      throw e;
    }
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public <T extends Obj> T fetchTypedObj(
      @Nonnull @jakarta.annotation.Nonnull ObjId id, ObjType type, Class<T> typeClass)
      throws ObjNotFoundException {
    Obj o = cache.get(id);
    if (o != null) {
      if (o.type() != type) {
        throw new ObjNotFoundException(id);
      }
    } else {
      o = persist.fetchTypedObj(id, type, typeClass);
      cache.put(o);
    }
    @SuppressWarnings("unchecked")
    T r = (T) o;
    return r;
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public ObjType fetchObjType(@Nonnull @jakarta.annotation.Nonnull ObjId id)
      throws ObjNotFoundException {
    Obj o = cache.get(id);
    if (o != null) {
      return o.type();
    }
    // 'fetchObjType' is used to validate the object-type for objects that are not available.
    // It's not worth to eagerly fetch the whole object and add it to the cache.
    return persist.fetchObjType(id);
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public Obj[] fetchObjs(@Nonnull @jakarta.annotation.Nonnull ObjId[] ids)
      throws ObjNotFoundException {
    ObjId[] backendIds = null;
    Obj[] r = new Obj[ids.length];

    for (int i = 0; i < ids.length; i++) {
      ObjId id = ids[i];
      if (id == null) {
        continue;
      }
      Obj o = cache.get(id);
      if (o != null) {
        r[i] = o;
      } else {
        if (backendIds == null) {
          backendIds = new ObjId[ids.length];
        }
        backendIds[i] = id;
      }
    }

    if (backendIds == null) {
      return r;
    }

    Obj[] backendResult = persist.fetchObjs(backendIds);
    for (int i = 0; i < backendResult.length; i++) {
      Obj o = backendResult[i];
      if (o != null) {
        r[i] = o;
        cache.put(o);
      }
    }
    return r;
  }

  @Override
  public boolean storeObj(
      @jakarta.annotation.Nonnull @Nonnull Obj obj, boolean ignoreSoftSizeRestrictions)
      throws ObjTooLargeException {
    if (persist.storeObj(obj, ignoreSoftSizeRestrictions)) {
      cache.put(obj);
      return true;
    }
    return false;
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public boolean[] storeObjs(@jakarta.annotation.Nonnull @Nonnull Obj[] objs)
      throws ObjTooLargeException {
    boolean[] stored = persist.storeObjs(objs);
    for (int i = 0; i < stored.length; i++) {
      if (stored[i]) {
        cache.put(objs[i]);
      }
    }
    return stored;
  }

  @Override
  public void updateObj(@jakarta.annotation.Nonnull @Nonnull Obj obj)
      throws ObjTooLargeException, ObjNotFoundException {
    try {
      persist.updateObj(obj);
    } finally {
      cache.remove(obj.id());
    }
  }

  @Override
  public void updateObjs(@jakarta.annotation.Nonnull @Nonnull Obj[] objs)
      throws ObjTooLargeException, ObjNotFoundException {
    try {
      persist.updateObjs(objs);
    } finally {
      for (Obj obj : objs) {
        if (obj != null) {
          cache.remove(obj.id());
        }
      }
    }
  }

  @Override
  public void deleteObj(@jakarta.annotation.Nonnull @Nonnull ObjId id) {
    try {
      persist.deleteObj(id);
    } finally {
      cache.remove(id);
    }
  }

  @Override
  public void deleteObjs(@jakarta.annotation.Nonnull @Nonnull ObjId[] ids) {
    try {
      persist.deleteObjs(ids);
    } finally {
      for (ObjId id : ids) {
        if (id != null) {
          cache.remove(id);
        }
      }
    }
  }

  @Override
  public void erase() {
    try {
      persist.erase();
    } finally {
      cache.clear();
    }
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public CloseableIterator<Obj> scanAllObjects(
      @Nonnull @jakarta.annotation.Nonnull Set<ObjType> returnedObjTypes) {
    return persist.scanAllObjects(returnedObjTypes);
  }

  // plain delegates...

  @Override
  public int hardObjectSizeLimit() {
    return persist.hardObjectSizeLimit();
  }

  @Override
  public int effectiveIndexSegmentSizeLimit() {
    return persist.effectiveIndexSegmentSizeLimit();
  }

  @Override
  public int effectiveIncrementalIndexSizeLimit() {
    return persist.effectiveIncrementalIndexSizeLimit();
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public StoreConfig config() {
    return persist.config();
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public String name() {
    return persist.name();
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public Reference addReference(@Nonnull @jakarta.annotation.Nonnull Reference reference)
      throws RefAlreadyExistsException {
    return persist.addReference(reference);
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public Reference markReferenceAsDeleted(@Nonnull @jakarta.annotation.Nonnull Reference reference)
      throws RefNotFoundException, RefConditionFailedException {
    return persist.markReferenceAsDeleted(reference);
  }

  @Override
  public void purgeReference(@Nonnull @jakarta.annotation.Nonnull Reference reference)
      throws RefNotFoundException, RefConditionFailedException {
    persist.purgeReference(reference);
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public Reference updateReferencePointer(
      @Nonnull @jakarta.annotation.Nonnull Reference reference,
      @Nonnull @jakarta.annotation.Nonnull ObjId newPointer)
      throws RefNotFoundException, RefConditionFailedException {
    return persist.updateReferencePointer(reference, newPointer);
  }

  @Override
  public Reference fetchReference(@Nonnull @jakarta.annotation.Nonnull String name) {
    return persist.fetchReference(name);
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public Reference[] fetchReferences(@Nonnull @jakarta.annotation.Nonnull String[] names) {
    return persist.fetchReferences(names);
  }
}