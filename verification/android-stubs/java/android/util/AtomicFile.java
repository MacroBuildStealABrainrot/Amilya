package android.util;

public class AtomicFile {
public AtomicFile(java.io.File f) {}
 public java.io.FileOutputStream startWrite() throws java.io.IOException { return null; }
 public void finishWrite(java.io.FileOutputStream s) {}
 public void failWrite(java.io.FileOutputStream s) {}
 public java.io.FileInputStream openRead() throws java.io.FileNotFoundException { return null; }
 public void delete() {}
}
