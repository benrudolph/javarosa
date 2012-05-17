package org.javarosa.j2me.storage.rms;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.NoSuchElementException;
import java.util.Vector;

import org.javarosa.core.model.instance.TreeReferenceLevel;
import org.javarosa.core.services.storage.IMetaData;
import org.javarosa.core.services.storage.IStorageIterator;
import org.javarosa.core.services.storage.IStorageUtilityIndexed;
import org.javarosa.core.services.storage.Persistable;
import org.javarosa.core.services.storage.StorageFullException;
import org.javarosa.core.util.DataUtil;
import org.javarosa.core.util.InvalidIndexException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;
import org.javarosa.j2me.storage.rms.raw.RMSFactory;

/* TEMPORARY / EXPERIMENTAL */

public class RMSStorageUtilityIndexed<E extends Externalizable> extends RMSStorageUtility<E> implements IStorageUtilityIndexed<E> {

	private Object metadataAccessLock = new Object();
	Hashtable metaDataIndex = null;
	boolean hasMetaData;
	IMetaData proto;
	
	public RMSStorageUtilityIndexed (String basename, Class type) {
		super(basename, type);
		init(type);
	}

	public RMSStorageUtilityIndexed (String basename, Class type, boolean allocateIDs, RMSFactory factory) {
		super(basename, type, allocateIDs, factory);
		init(type);
	}

	private void init (Class type) {
		hasMetaData = IMetaData.class.isAssignableFrom(type);
		if (hasMetaData) {
			proto = (IMetaData)PrototypeFactory.getInstance(type);
		}
	}	
	
	private void checkIndex () {
		synchronized(metadataAccessLock) {
			if (metaDataIndex == null) {
				buildIndex();
			}
		}
	}
	
	private void buildIndex () {
		synchronized(metadataAccessLock) {
			//Temporarily stop doing any interning since we don't want to increase memory fragmentation
			//for objects we're just going to throw out anyway
			boolean oldterning = ExtUtil.interning;
			boolean otrt = TreeReferenceLevel.treeRefLevelInterningEnabled;
			ExtUtil.interning = false;
			TreeReferenceLevel.treeRefLevelInterningEnabled = false;
			metaDataIndex = new Hashtable();
			
			if (!hasMetaData) {
				return;
			}
			
			String[] fields = proto.getMetaDataFields();
			for (int k = 0; k < fields.length; k++) {
				metaDataIndex.put(fields[k], new Hashtable());
			}
			
			IStorageIterator i = iterate();
			int records = this.getNumRecords();
			Hashtable[] metadata = new Hashtable[records];
			int[] recordIds = new int[records];
			for(int j = 0 ; j < records ; ++j) {
				metadata[j] = new Hashtable(fields.length); 
				for(String field : fields) {
					metadata[j].put(field, "");
				}
			}
			int count = 0;
			IMetaData obj;
			while (i.hasMore()) {
				recordIds[count] = i.nextID();
				count++;
			}
			for(int index = 0 ; index < recordIds.length; ++ index) {
				obj = (IMetaData)read(recordIds[index]);
				
				copyHT(metadata[index], obj.getMetaData(), fields);
				
				obj = null;
				System.gc();
			}
			for(int index = 0; index < recordIds.length; ++index) {
				indexMetaData(recordIds[index], metadata[index]);
			}
			ExtUtil.interning = oldterning;
			TreeReferenceLevel.treeRefLevelInterningEnabled = otrt;
		}
	}
	
	private int i = 0;
	private void copyHT(Hashtable into, Hashtable source, String[] fields) {
		for(i = 0; i < fields.length ; ++i) {
			into.put(fields[i], source.get(fields[i]));
		}
	}
	
	private void indexMetaData (int id, Hashtable vals) {
		for (Enumeration e = vals.keys(); e.hasMoreElements(); ) {
			String field = (String)e.nextElement();
			Object val = vals.get(field);
			
			Vector IDs = getIDList(field, val);
			if (IDs.contains(DataUtil.integer(id))) {
				System.out.println("warning: don't think this should happen [add] [" + id + ":" + field + ":" + val.toString() + "]");
			}
			IDs.addElement(DataUtil.integer(id));
		}
	}
	
	private void removeMetaData (int id, IMetaData obj) {
		Hashtable vals = obj.getMetaData();
		for (Enumeration e = vals.keys(); e.hasMoreElements(); ) {
			String field = (String)e.nextElement();
			Object val = vals.get(field);
			
			Vector IDs = getIDList(field, val);
			if (!IDs.contains(new Integer(id))) {
				System.out.println("warning: don't think this should happen [remove] [" + id + ":" + field + ":" + val.toString() + "]");
			}
			IDs.removeElement(new Integer(id));
			if (IDs.size() == 0) {
				((Hashtable)(metaDataIndex.get(field))).remove(val);
			}
		}
	}
	
	private Vector getIDList (String field, Object value) {
		Vector IDs;
		synchronized(metadataAccessLock) {
			IDs = (Vector)((Hashtable)(metaDataIndex.get(field))).get(value);
			if (IDs == null) {
				IDs = new Vector();
				((Hashtable)(metaDataIndex.get(field))).put(value, IDs);
			}
		}
		return IDs;
	}
	
	public void write (Persistable p) throws StorageFullException {
		IMetaData old = null;
		synchronized(metadataAccessLock) {
			if (hasMetaData) {
				checkIndex();
				if (exists(p.getID())) {
					old = (IMetaData)read(p.getID());
				}
			}
			
			super.write(p);
			
			if (hasMetaData) {
				if (old != null) {
					removeMetaData(p.getID(), (IMetaData)old);
				}
				indexMetaData(p.getID(), ((IMetaData)p).getMetaData());
			}
		}
	}
	
	public int add (E e) throws StorageFullException {
		synchronized(metadataAccessLock) {
			if (hasMetaData) {
				checkIndex();
			}
	
			int id = super.add(e);
			
			if (hasMetaData) {
				indexMetaData(id, ((IMetaData)e).getMetaData());
			}
			return id;
		}
	}
	
	public void update (int id, E e) throws StorageFullException {
		synchronized(metadataAccessLock) {

			Externalizable old;
			if (hasMetaData) {
				old = read(id);
				checkIndex();
				removeMetaData(id, (IMetaData)old);
			}
			
			super.update(id, e);
			
			if (hasMetaData) {
				indexMetaData(id, ((IMetaData)e).getMetaData());
			}
		}
	}
	
	public void remove (int id) {
		synchronized(metadataAccessLock) {
			Externalizable old = null;
			if (hasMetaData) {
				old = read(id);
				checkIndex();
			}
				
			super.remove(id);
			
			if (hasMetaData) {
				removeMetaData(id, (IMetaData)old);
			}
		}
	}
	
	public Vector getIDsForValue (String fieldName, Object value) {
		synchronized(metadataAccessLock) {
			checkIndex();
	
			Hashtable index = (Hashtable)metaDataIndex.get(fieldName);
			if (index == null) {
				throw new RuntimeException("field [" + fieldName + "] not recognized");
			}
			
			Vector IDs = (Vector)index.get(value);
			return (IDs == null ? new Vector(): IDs);
		}
	}
	
	public E getRecordForValue (String fieldName, Object value) throws NoSuchElementException {
		synchronized(metadataAccessLock) {
			Vector IDs = getIDsForValue(fieldName, value);
			if (IDs.size() == 1) {
				return read(((Integer)IDs.elementAt(0)).intValue());
			} else if (IDs.size() == 0){
				throw new NoSuchElementException("Storage utility " + getName() +  " does not contain any records with the index " + fieldName + " equal to " + value.toString());
			} else {
				throw new InvalidIndexException(fieldName + " is not a valid unique index. More than one record was found with value [" + value.toString() + "] in field [" + fieldName + "]",fieldName);
			}
		}
	}
	
	public void clearIndexCache() {
		synchronized(metadataAccessLock) {
			if(metaDataIndex != null) {
				metaDataIndex = null;
			}
		}
	}
}

