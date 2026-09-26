package com.airlineprep.bot.analytics;
import java.io.*;
import java.util.*;
public final class SafeCsv {
 private SafeCsv() {}
 public static String cell(String value) {
  String s=value==null?"":value;
  String probe=s.replaceAll("^[\\s\\p{Z}\\p{Cf}\\p{Cc}]+","");
  if(!probe.isEmpty()&&"=+-@".indexOf(probe.charAt(0))>=0 || s.startsWith("\t")||s.startsWith("\r")||s.startsWith("\n")) s="'"+s;
  return "\""+s.replace("\"","\"\"")+"\"";
 }
 public static void row(Writer writer,List<String> cells) throws IOException {
  for(int i=0;i<cells.size();i++) {if(i>0) writer.write(',');writer.write(cell(cells.get(i)));}writer.write("\r\n");
 }
}
