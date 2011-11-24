package net.henryhu.roxlab2;

public class Entry<K, V> {

	private final K mKey;
	private final V mValue;

	public Entry(K k,V v) {  
		mKey = k;
		mValue = v;   
	}

	public K getKey() {
		return mKey;
	}

	public V getValue() {
		return mValue;
	}

	public String toString() { 
		return "(" + mKey + ", " + mValue + ")";  
	}
}