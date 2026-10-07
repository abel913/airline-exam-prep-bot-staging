package com.airlineprep.bot.admin;
import java.security.Principal;
import com.airlineprep.bot.question.*;
import com.airlineprep.bot.examtype.ExamTypeRepository;
import com.airlineprep.bot.category.CategoryRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.*;
import org.springframework.web.multipart.*;
@Controller @RequestMapping("/admin/questions")
public class QuestionController {
 private final QuestionService questions;
 private final QuestionImportService imports;
 private final QuestionFileParser parser;
 private final ExamTypeRepository exams;
 private final CategoryRepository categories;
 private final QuestionImportBulkService bulk;
 private final QuestionBulkDeleteService bulkDelete;
 private final QuestionImportRemovalService importRemoval;
 public QuestionController(QuestionService q,QuestionImportService i,QuestionFileParser p,ExamTypeRepository e,CategoryRepository c,QuestionImportBulkService bulk,QuestionBulkDeleteService bulkDelete,QuestionImportRemovalService importRemoval) {
  questions=q;imports=i;parser=p;exams=e;categories=c;this.bulk=bulk;this.bulkDelete=bulkDelete;this.importRemoval=importRemoval;
 }
 @InitBinder("form")
 void binder(org.springframework.web.bind.WebDataBinder binder) { binder.setAutoGrowCollectionLimit(8); }
 private void choices(Model m) {
  m.addAttribute("exams",exams.findAllByOrderByDisplayOrderAscIdAsc());
  m.addAttribute("categories",categories.findAllByOrderByDisplayOrderAscIdAsc());
  m.addAttribute("statuses",QuestionStatus.values());m.addAttribute("rights",UseStatus.values());m.addAttribute("difficulties",Difficulty.values());
 }
 @GetMapping
 String list(@RequestParam(defaultValue="") String text,@RequestParam(required=false) Long exam,
  @RequestParam(required=false) Long category,@RequestParam(required=false) QuestionStatus status,
  @RequestParam(required=false) String pool,@RequestParam(required=false) UseStatus rights,
  @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="updatedAt") String sort,
  @RequestParam(required=false) Difficulty difficulty,@RequestParam(defaultValue="") String tag,Model m) {
  choices(m);m.addAttribute("rows",questions.search(text,exam,category,status,pool,rights,page,sort,difficulty,tag));
  m.addAttribute("difficulty",difficulty);m.addAttribute("tag",tag);
  m.addAttribute("text",text);m.addAttribute("exam",exam);m.addAttribute("category",category);m.addAttribute("status",status);m.addAttribute("pool",pool);m.addAttribute("useStatus",rights);m.addAttribute("sort",sort);
  return "admin/question-list";
 }
 @GetMapping("/bulk-delete-non-published")
 String bulkDeleteConfirm(Model m) { m.addAttribute("plan",bulkDelete.preview());return "admin/question-bulk-delete-confirm"; }
 @PostMapping("/bulk-delete-non-published")
 String bulkDelete(@RequestParam(defaultValue="") String confirmation,Principal actor,Model m,RedirectAttributes flash) {
  var plan=bulkDelete.preview();
  String expected="DELETE "+plan.eligible()+" NON-PUBLISHED QUESTIONS";
  if(!confirmation.equals(expected)) {
   flash.addFlashAttribute("error","Type the exact confirmation phrase shown below to continue.");
   return "redirect:/admin/questions/bulk-delete-non-published";
  }
  try { m.addAttribute("result",bulkDelete.deleteAll(actor.getName(),plan.eligible()));return "admin/question-bulk-delete-result"; }
  catch(IllegalArgumentException e) {
   flash.addFlashAttribute("error",e.getMessage());
   return "redirect:/admin/questions/bulk-delete-non-published";
  }
 }
 @GetMapping("/new")
 String create(Model m) { m.addAttribute("form",new QuestionForm());m.addAttribute("action","/admin/questions/new");choices(m);return "admin/question-form"; }
 @GetMapping("/{id}")
 String detail(@PathVariable long id,@RequestParam(defaultValue="0") int page,Model m) {
  m.addAttribute("question",questions.get(id));m.addAttribute("history",questions.history(id,page));return "admin/question-detail";
 }
 @PostMapping("/{id}/restore")
 String restore(@PathVariable long id,@RequestParam(defaultValue="false") boolean confirm,Principal actor,RedirectAttributes flash) {
  if(!confirm) { flash.addFlashAttribute("error","Confirm restoration to Draft before continuing.");return "redirect:/admin/questions/"+id; }
  try { questions.restoreToDraft(id,actor.getName()); }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
  return "redirect:/admin/questions/"+id;
 }
 @PostMapping("/{id}/delete")
 String deleteQuestion(@PathVariable long id,@RequestParam Long expectedRevision,@RequestParam(defaultValue="false") boolean confirm,Principal actor,RedirectAttributes flash) {
  if(!confirm) { flash.addFlashAttribute("error","Confirm permanent deletion before continuing.");return "redirect:/admin/questions/"+id; }
  try { questions.deleteSafeDraft(id,expectedRevision,actor.getName());return "redirect:/admin/questions?deleted"; }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage());return "redirect:/admin/questions/"+id; }
 }
 @GetMapping("/{id}/edit")
 String edit(@PathVariable long id,Model m) { m.addAttribute("form",questions.form(id));m.addAttribute("action","/admin/questions/"+id+"/edit");choices(m);return "admin/question-form"; }
 @PostMapping("/new")
 String create(@ModelAttribute("form") QuestionForm f,BindingResult errors,Model m,Principal actor) { return save(null,f,errors,m,actor); }
 @PostMapping("/{id}/edit")
 String edit(@PathVariable long id,@ModelAttribute("form") QuestionForm f,BindingResult errors,Model m,Principal actor) { return save(id,f,errors,m,actor); }
 private String save(Long id,QuestionForm f,BindingResult errors,Model m,Principal actor) {
  if(!errors.hasErrors()) try { return "redirect:/admin/questions/"+questions.save(id,f,actor.getName()); }
  catch(IllegalArgumentException e) { errors.reject("question.invalid",e.getMessage()); }
  choices(m);m.addAttribute("action",id==null?"/admin/questions/new":"/admin/questions/"+id+"/edit");return "admin/question-form";
 }
 @PostMapping("/{id}/transition")
 String transition(@PathVariable long id,@RequestParam QuestionStatus target,@RequestParam Long expectedRevision,Principal actor,RedirectAttributes flash) {
  try { questions.transition(id,target,expectedRevision,actor.getName()); }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
  return "redirect:/admin/questions/"+id;
 }
 @GetMapping("/import")
 String history(@RequestParam(defaultValue="0") int page,Model m) { m.addAttribute("batches",imports.history(page));return "admin/question-import"; }
 @PostMapping("/import")
 String upload(@RequestParam MultipartFile file,Principal actor,RedirectAttributes flash) {
  try { return "redirect:/admin/questions/import/"+imports.stage(imports.parse(file),actor.getName()); }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage());return "redirect:/admin/questions/import"; }
 }
 @GetMapping("/import/{id}")
 String preview(@PathVariable long id,@RequestParam(defaultValue="0") int page,Model m) {
  m.addAttribute("batch",imports.get(id));m.addAttribute("rows",imports.preview(id,page));m.addAttribute("questionCounts",imports.questionCounts(id));
  m.addAttribute("removalPlan",importRemoval.preview(id));return "admin/question-preview";
 }
 @GetMapping("/import/{id}/remove-questions")
 String removeImportedQuestionsConfirm(@PathVariable long id,Model m) {
  m.addAttribute("batch",imports.get(id));m.addAttribute("plan",importRemoval.preview(id));return "admin/question-import-remove-confirm";
 }
 @PostMapping("/import/{id}/remove-questions")
 String removeImportedQuestions(@PathVariable long id,@RequestParam(defaultValue="false") boolean confirm,Principal actor,Model m,RedirectAttributes flash) {
  if(!confirm) { flash.addFlashAttribute("error","Confirm removal of the questions created by this import.");return "redirect:/admin/questions/import/"+id+"/remove-questions"; }
  try { m.addAttribute("result",importRemoval.remove(id,actor.getName()));return "admin/question-import-remove-result"; }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage());return "redirect:/admin/questions/import/"+id; }
 }
 @PostMapping("/import/{id}/review-publish")
 String reviewPublish(@PathVariable long id,@RequestParam(defaultValue="false") boolean confirm,Principal actor,RedirectAttributes flash) {
  if(!confirm) { flash.addFlashAttribute("error","Confirm review and publication before continuing.");return "redirect:/admin/questions/import/"+id; }
  try { flash.addFlashAttribute("bulkResult",bulk.reviewAndPublish(id,actor.getName())); }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
  return "redirect:/admin/questions/import/"+id;
 }
 @PostMapping("/import/{id}/delete")
 String deleteImport(@PathVariable long id,@RequestParam(defaultValue="false") boolean confirm,Principal actor,RedirectAttributes flash) {
  if(!confirm) { flash.addFlashAttribute("error","Confirm import-history deletion before continuing.");return "redirect:/admin/questions/import/"+id; }
  try { imports.deleteHistory(id,actor.getName());return "redirect:/admin/questions/import?deleted"; }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage());return "redirect:/admin/questions/import/"+id; }
 }
 @PostMapping("/import/{id}/clear-invalid")
 String clearInvalid(@PathVariable long id,@RequestParam(defaultValue="false") boolean confirm,Principal actor,RedirectAttributes flash) {
  if(!confirm) { flash.addFlashAttribute("error","Confirm removal of invalid staging rows before continuing.");return "redirect:/admin/questions/import/"+id; }
  try { flash.addFlashAttribute("cleanupCount",imports.clearInvalidRows(id,actor.getName())); }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
  return "redirect:/admin/questions/import/"+id;
 }
 @PostMapping("/import/clear-cancelled-failed")
 String clearImports(@RequestParam(defaultValue="false") boolean confirm,Principal actor,RedirectAttributes flash) {
  if(!confirm) { flash.addFlashAttribute("error","Confirm cancelled and failed history cleanup before continuing.");return "redirect:/admin/questions/import"; }
  int count=imports.clearCancelledFailed(actor.getName());flash.addFlashAttribute("cleanupCount",count);return "redirect:/admin/questions/import?cleaned";
 }
 @PostMapping("/import/{id}/{action:confirm|cancel}")
 String confirm(@PathVariable long id,@PathVariable String action,Principal actor,RedirectAttributes flash) {
  try { if(action.equals("confirm")) imports.confirm(id,actor.getName());else imports.cancel(id,actor.getName()); }
  catch(IllegalArgumentException e) { flash.addFlashAttribute("error",e.getMessage()); }
  return "redirect:/admin/questions/import/"+id;
 }
 @GetMapping("/import/template.csv") @ResponseBody
 ResponseEntity<byte[]> template() {
  return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"question-template.csv\"")
   .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(parser.template());
 }
 @ExceptionHandler(MaxUploadSizeExceededException.class)
 String tooLarge(RedirectAttributes flash) { flash.addFlashAttribute("error","Upload a file of at most 2 MiB.");return "redirect:/admin/questions/import"; }
}
