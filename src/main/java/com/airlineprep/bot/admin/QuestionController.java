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
 public QuestionController(QuestionService q,QuestionImportService i,QuestionFileParser p,ExamTypeRepository e,CategoryRepository c) {
  questions=q;imports=i;parser=p;exams=e;categories=c;
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
 @GetMapping("/new")
 String create(Model m) { m.addAttribute("form",new QuestionForm());m.addAttribute("action","/admin/questions/new");choices(m);return "admin/question-form"; }
 @GetMapping("/{id}")
 String detail(@PathVariable long id,@RequestParam(defaultValue="0") int page,Model m) {
  m.addAttribute("question",questions.get(id));m.addAttribute("history",questions.history(id,page));return "admin/question-detail";
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
  m.addAttribute("batch",imports.get(id));m.addAttribute("rows",imports.preview(id,page));return "admin/question-preview";
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
