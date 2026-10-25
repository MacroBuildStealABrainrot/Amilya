package org.json;

public class JSONObject {
public static final Object NULL = new Object();
 public JSONObject() {}
 public JSONObject(String s) throws JSONException {}
 public JSONObject put(String n, Object v) throws JSONException { return this; }
 public JSONObject put(String n, long v) throws JSONException { return this; }
 public JSONObject put(String n, boolean v) throws JSONException { return this; }
 public String optString(String n, String fallback) { return fallback; }
 public String optString(String n) { return ""; }
 public long optLong(String n) { return 0; }
 public int optInt(String n) { return 0; }
 public boolean optBoolean(String n) { return false; }
 public JSONObject optJSONObject(String n) { return null; }
 public JSONArray optJSONArray(String n) { return null; }
 public String getString(String n) throws JSONException { return null; }
 public JSONObject getJSONObject(String n) throws JSONException { return null; }
 public JSONArray getJSONArray(String n) throws JSONException { return null; }
 public boolean has(String n) { return false; }
 public boolean isNull(String n) { return true; }
}
