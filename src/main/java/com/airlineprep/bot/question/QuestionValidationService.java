package com.airlineprep.bot.question;
import java.util.*;
import com.airlineprep.bot.examtype.*;
import com.airlineprep.bot.category.*;
import org.springframework.stereotype.Service;
@Service
public class QuestionValidationService {
 private final ExamTypeRepository exams;
 private final CategoryRepository categories;
 public QuestionValidationService(ExamTypeRepository e, CategoryRepository c) { exams=e; categories=c; }
 public List<String> errors(QuestionForm f, boolean complete, boolean rightsRequired) {
  List<String> errors=new ArrayList<>();
  try { f.setTags(QuestionTags.normalize(f.getTags())); } catch(IllegalArgumentException e) { errors.add(e.getMessage()); }
  var exam=f.examTypeId==null?null:exams.findById(f.examTypeId).orElse(null);
  var cat=f.categoryId==null?null:categories.findById(f.categoryId).orElse(null);
  if((complete || f.examTypeId!=null) && exam==null) errors.add("Choose an existing exam type.");
  if((complete || f.categoryId!=null) && cat==null) errors.add("Choose an existing category.");
  if(cat!=null && !Objects.equals(cat.getExamTypeId(),f.examTypeId)) errors.add("Category must belong to the selected exam type.");
  if(complete && ((exam!=null&&!exam.getActive()) || (cat!=null&&!cat.getActive()))) errors.add("Exam type and category must be active.");
  length(errors,"Question",f.questionText,12000);
  length(errors,"Explanation",f.explanation,12000);
  length(errors,"Source type",f.sourceType,100);
  length(errors,"Source title",f.sourceTitle,300);
  length(errors,"Source reference",f.sourceReference,500);
  length(errors,"Source notes",f.sourceNotes,2000);
  if(f.difficulty==null || f.useStatus==null) errors.add("Choose valid difficulty and use status.");
  if(f.sourceYear!=null && (f.sourceYear<1000 || f.sourceYear>9999)) errors.add("Source year must have four digits.");
  List<String> options=f.getOptions();
  if(options==null || options.size()>8) errors.add("Use at most eight options.");
  else {
   long count=options.stream().filter(s->s!=null&&!s.isBlank()).count();
   for(String s: options) length(errors,"Option",s,2000);
   Integer correct=f.getCorrectOption();
   if(correct!=null && (correct<0 || correct>=options.size() || options.get(correct)==null || options.get(correct).isBlank())) errors.add("The correct answer must identify a nonblank option.");
   if(complete) {
    if(count<2) errors.add("At least two options are required.");
    if(correct==null) errors.add("Exactly one correct answer is required.");
    if(options.stream().filter(s->s!=null&&!s.isBlank()).map(QuestionDuplicateService::normalize).distinct().count()!=count) errors.add("Answer options must be distinct.");
   }
  }
  if(complete) {
   if(f.questionText==null || f.questionText.isBlank()) errors.add("Question text is required.");
   if(f.explanation==null || f.explanation.isBlank()) errors.add("Explanation is required.");
   if(f.difficulty==Difficulty.UNSPECIFIED) errors.add("Choose a difficulty.");
   if(f.sourceType==null || f.sourceType.isBlank() || f.sourceTitle==null || f.sourceTitle.isBlank()) errors.add("Source type and title are required.");
   if(!f.freePool&&!f.premiumPool&&!f.mockPool) errors.add("Select at least one pool.");
   if(rightsRequired && (f.useStatus==UseStatus.BLOCKED || f.useStatus==UseStatus.UNKNOWN_REVIEW_REQUIRED)) errors.add("Resolve content rights before review or publication.");
  }
  return errors;
 }
 private void length(List<String> errors,String name,String value,int max) {
  if(value==null || value.length()>max || value.indexOf('\0')>=0) errors.add(name+" must contain at most "+max+" characters and no null bytes.");
 }
 public void validate(QuestionForm f, boolean complete) {
  var errors=errors(f,complete,complete);
  if(!errors.isEmpty()) throw new IllegalArgumentException(String.join(" ",errors));
 }
}
