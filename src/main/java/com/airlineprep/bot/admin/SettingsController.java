package com.airlineprep.bot.admin;

import java.security.Principal;
import jakarta.validation.Valid;
import com.airlineprep.bot.settings.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/settings")
public class SettingsController {
    private final SettingsService settings;
    public SettingsController(SettingsService settings) { this.settings=settings; }
    @GetMapping
    public String show(Model model) {
        model.addAttribute("form",SettingsForm.from(settings.current()));
        return "admin/settings";
    }
    @PostMapping
    public String save(@Valid @ModelAttribute("form") SettingsForm form, BindingResult result, Principal admin) {
        if (result.hasErrors()) return "admin/settings";
        settings.update(form,admin.getName());
        return "redirect:/admin/settings?saved";
    }

    @GetMapping("/study-reminders")
    public String studyReminders(Model model) {
        var current=settings.current();
        var form=StudyReminderCampaignForm.from(current);
        model.addAttribute("campaignForm",form);
        model.addAttribute("campaignStatus",status(current));
        model.addAttribute("updatedAt",formatTime(current.getStudyRemindersUpdatedAt()));
        model.addAttribute("updatedBy",current.getStudyRemindersUpdatedBy());
        model.addAttribute("displayZone",StudyReminderCampaignForm.DISPLAY_ZONE.getId());
        return "admin/study-reminders";
    }

    @PostMapping("/study-reminders")
    public String saveStudyReminders(@ModelAttribute("campaignForm") StudyReminderCampaignForm form,
                                    org.springframework.ui.Model model, Principal admin) {
        String error=form.validationError();
        if(error!=null) {
            model.addAttribute("campaignForm",form);
            model.addAttribute("campaignStatus","INVALID");
            model.addAttribute("error",error);
            model.addAttribute("updatedAt",formatTime(settings.current().getStudyRemindersUpdatedAt()));
            model.addAttribute("updatedBy",settings.current().getStudyRemindersUpdatedBy());
            model.addAttribute("displayZone",StudyReminderCampaignForm.DISPLAY_ZONE.getId());
            return "admin/study-reminders";
        }
        settings.updateStudyReminderCampaign(form,admin.getName());
        return "redirect:/admin/settings/study-reminders?saved";
    }

    private String status(com.airlineprep.bot.settings.AppSettings settings) {
        if(!settings.isStudyRemindersGloballyEnabled()) return "OFF";
        var now=java.time.Instant.now();
        if(settings.getStudyRemindersStartAt()==null||settings.getStudyRemindersEndAt()==null) return "OFF";
        if(now.isBefore(settings.getStudyRemindersStartAt())) return "SCHEDULED";
        if(now.isAfter(settings.getStudyRemindersEndAt())) return "EXPIRED";
        return "ACTIVE";
    }

    private String formatTime(java.time.Instant value) {
        return value==null ? null : java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm z")
            .format(value.atZone(StudyReminderCampaignForm.DISPLAY_ZONE));
    }
}
