package com.airlineprep.bot.question;
import java.text.Normalizer;
import java.util.*;
public final class QuestionTags {
 private QuestionTags() {}
 public static String normalize(String input) {
  if(input==null||input.isBlank()) return "";
  if(input.length()>400) throw new IllegalArgumentException("Use at most eight tags of 32 characters.");
  Set<String> tags=new LinkedHashSet<>();
  for(String part:input.split(",",-1)) {
   String tag=Normalizer.normalize(part,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll(" +"," ");
   if(!tag.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} -]{0,31}")) throw new IllegalArgumentException("Tags use letters, numbers, spaces or hyphens, up to 32 characters each.");
   tags.add(tag);
  }
  if(tags.size()>8) throw new IllegalArgumentException("Use at most eight tags.");
  return String.join(",",tags);
 }
}
