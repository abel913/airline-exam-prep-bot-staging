package com.airlineprep.bot.admin;

import java.security.Principal;
import jakarta.validation.Valid;
import com.airlineprep.bot.examtype.ExamTypeRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/{kind:exam-types|categories}")
public class CatalogController {
    private final CatalogService catalog;
    private final ExamTypeRepository exams;
    public CatalogController(CatalogService catalog, ExamTypeRepository exams) { this.catalog=catalog; this.exams=exams; }
    boolean category(String kind) { return "categories".equals(kind); }
    void model(String kind, Model model) {
        model.addAttribute("kind",kind);
        model.addAttribute("title",category(kind) ? "Categories" : "Exam types");
        model.addAttribute("examChoices",exams.findAllByOrderByDisplayOrderAscIdAsc());
    }
    @GetMapping
    public String list(@PathVariable String kind, Model model) {
        model(kind,model); model.addAttribute("rows",catalog.list(category(kind))); return "admin/catalog-list";
    }
    @GetMapping("/new")
    public String create(@PathVariable String kind, Model model) {
        model(kind,model);
        model.addAttribute("form",new CatalogForm("","","",true,0,null));
        model.addAttribute("action","/admin/"+kind+"/new"); return "admin/catalog-form";
    }
    @GetMapping("/{id}/edit")
    public String edit(@PathVariable String kind, @PathVariable long id, Model model) {
        model(kind,model); model.addAttribute("form",catalog.form(category(kind),id));
        model.addAttribute("action","/admin/"+kind+"/"+id+"/edit"); return "admin/catalog-form";
    }
    @PostMapping("/new")
    public String create(@PathVariable String kind, @Valid @ModelAttribute("form") CatalogForm form,
                         BindingResult result, Model model, Principal admin) {
        return save(kind,null,form,result,model,admin);
    }
    @PostMapping("/{id}/edit")
    public String edit(@PathVariable String kind, @PathVariable long id,
                       @Valid @ModelAttribute("form") CatalogForm form, BindingResult result, Model model, Principal admin) {
        return save(kind,id,form,result,model,admin);
    }
    private String save(String kind, Long id, CatalogForm form, BindingResult result, Model model, Principal admin) {
        if (!result.hasErrors()) {
            try { catalog.save(category(kind),id,form,admin.getName()); }
            catch (IllegalArgumentException exception) { result.reject("catalog.invalid",exception.getMessage()); }
        }
        if (result.hasErrors()) {
            model(kind,model);
            model.addAttribute("action","/admin/"+kind+(id == null ? "/new" : "/"+id+"/edit"));
            return "admin/catalog-form";
        }
        return "redirect:/admin/"+kind+"?saved";
    }
    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable String kind, @PathVariable long id, Principal admin) {
        catalog.toggle(category(kind),id,admin.getName()); return "redirect:/admin/"+kind+"?saved";
    }
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String kind,@PathVariable long id,@RequestParam(defaultValue="false") boolean confirm,Principal admin,org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        if (!category(kind)) return "redirect:/admin/"+kind;
        if (!confirm) { flash.addFlashAttribute("error","Confirm category deletion before continuing.");return "redirect:/admin/categories"; }
        try { catalog.deleteCategory(id,admin.getName()); return "redirect:/admin/categories?deleted"; }
        catch (IllegalArgumentException ex) { flash.addFlashAttribute("error",ex.getMessage());return "redirect:/admin/categories"; }
    }
}
