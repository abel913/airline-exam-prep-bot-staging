package com.airlineprep.bot.admin;

import java.util.List;
import com.airlineprep.bot.examtype.*;
import com.airlineprep.bot.category.*;
import com.airlineprep.bot.question.QuestionVersionRepository;
import com.airlineprep.bot.audit.AdminChangeService;
import com.airlineprep.bot.settings.SettingsService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Validated
@Transactional
public class CatalogService {
    private final ExamTypeRepository exams;
    private final CategoryRepository categories;
    private final SettingsService settings;
    private final AdminChangeService changes;
    private final QuestionVersionRepository versions;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public CatalogService(ExamTypeRepository exams, CategoryRepository categories, SettingsService settings,
                          AdminChangeService changes,QuestionVersionRepository versions,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.exams = exams; this.categories = categories; this.settings = settings; this.changes = changes;this.versions=versions;this.jdbc=jdbc;
    }
    public record Row(Long id, String code, String name, String nameAm, boolean active, int displayOrder, Long examTypeId) {}
    @Transactional(readOnly = true)
    public List<Row> list(boolean category) {
        return category ? categories.findAllByOrderByDisplayOrderAscIdAsc().stream()
            .map(c -> new Row(c.getId(),c.getCode(),c.getName(),c.getNameAm(),c.getActive(),c.getDisplayOrder(),c.getExamTypeId())).toList()
            : exams.findAllByOrderByDisplayOrderAscIdAsc().stream()
            .map(e -> new Row(e.getId(),e.getCode(),e.getName(),e.getNameAm(),e.getActive(),e.getDisplayOrder(),null)).toList();
    }
    @Transactional(readOnly = true)
    public CatalogForm form(boolean category, long id) {
        if (category) {
            var c = categories.findById(id).orElseThrow(this::notFound);
            return new CatalogForm(c.getCode(),c.getName(),c.getNameAm(),c.getActive(),c.getDisplayOrder(),c.getExamTypeId());
        }
        var e = exams.findById(id).orElseThrow(this::notFound);
        return new CatalogForm(e.getCode(),e.getName(),e.getNameAm(),e.getActive(),e.getDisplayOrder(),null);
    }
    public Long save(boolean category, Long id, @Valid CatalogForm form, String actor) {
        settings.lock();
        String before = id == null ? "" : form(category, id).toString();
        Long result;
        if (category) {
            if (form.examTypeId() == null || !exams.existsById(form.examTypeId()))
                throw new IllegalArgumentException("Choose an existing exam type.");
            if (categories.existsByExamTypeIdAndCodeAndIdNot(form.examTypeId(),form.code(),id == null ? -1L : id))
                throw new IllegalArgumentException("That category code is already used for this exam type.");
            Category c = id == null ? new Category() : categories.findById(id).orElseThrow(this::notFound);
            c.setExamTypeId(form.examTypeId()); c.setCode(form.code()); c.setName(form.name().strip());
            c.setNameAm(form.nameAm().strip()); c.setActive(form.active()); c.setDisplayOrder(form.displayOrder());
            result = categories.save(c).getId();
        } else {
            if (exams.existsByCodeAndIdNot(form.code(), id == null ? -1L : id))
                throw new IllegalArgumentException("That exam type code is already used.");
            ExamType e = id == null ? new ExamType() : exams.findById(id).orElseThrow(this::notFound);
            e.setCode(form.code()); e.setName(form.name().strip()); e.setNameAm(form.nameAm().strip());
            e.setActive(form.active()); e.setDisplayOrder(form.displayOrder());
            result = exams.save(e).getId();
        }
        changes.record(actor, id == null ? "CATALOG_CREATED" : "CATALOG_UPDATED",
            (category ? "category:" : "exam:") + result, before, form.toString());
        return result;
    }
    public void toggle(boolean category, long id, String actor) {
        settings.lock();
        CatalogForm old = form(category,id);
        save(category,id,new CatalogForm(old.code(),old.name(),old.nameAm(),!old.active(),old.displayOrder(),old.examTypeId()),actor);
        if(category&&old.active()) changes.record(actor,"CATEGORY_DEACTIVATED","category:"+id,old.name(),"inactive");
    }
    public void deleteCategory(long id,String actor) {
        settings.lock(); Category c=categories.findById(id).orElseThrow(this::notFound);
        long versionReferences=versions.countByContentCategoryId(id);
        long activityReferences=jdbc.queryForObject("SELECT COUNT(*) FROM practice_deliveries WHERE category_filter=?",Long.class,id);
        long references=versionReferences+activityReferences;
        if(references>0) throw new IllegalArgumentException("Cannot delete this category because "+references+" question history or student activity records reference it. Deactivate it instead.");
        String name=c.getName(); categories.delete(c); categories.flush();
        changes.record(actor,"CATEGORY_DELETED","category:"+id,name,"deleted");
    }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
