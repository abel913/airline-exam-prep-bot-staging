package com.airlineprep.bot.admin;

import com.airlineprep.bot.user.*;
import com.airlineprep.bot.examtype.ExamTypeRepository;
import com.airlineprep.bot.category.CategoryRepository;
import com.airlineprep.bot.settings.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminDashboardController {
    private final BotUserRepository users;
    private final com.airlineprep.bot.payment.PaymentQueries payments;
    private final ExamTypeRepository exams;
    private final CategoryRepository categories;
    private final SettingsService settings;
    private final com.airlineprep.bot.question.QuestionRepository questions;
    public AdminDashboardController(BotUserRepository users, ExamTypeRepository exams,
                                    CategoryRepository categories, SettingsService settings,
                                    com.airlineprep.bot.question.QuestionRepository questions,
                                    com.airlineprep.bot.payment.PaymentQueries payments) {
        this.payments=payments;
        this.questions=questions;
        this.users=users; this.exams=exams; this.categories=categories; this.settings=settings;
    }
    @GetMapping("/admin/login")
    public String login() { return "admin/login"; }
    @GetMapping("/admin")
    public String dashboard(Model model) {
        model.addAttribute("maintenance",settings.current().getMaintenanceEnabled());
        model.addAttribute("paymentCounts",payments.counts());
        model.addAttribute("questionTotal",questions.count());
        java.util.Map<String,Long> counts=new java.util.LinkedHashMap<>();
        for(var status:com.airlineprep.bot.question.QuestionStatus.values()) counts.put(status.name(),questions.countByStatus(status));
        model.addAttribute("questionCounts",counts);
        model.addAttribute("users",users.count());
        model.addAttribute("completed",users.countByRegistrationStatus(RegistrationStatus.COMPLETED));
        model.addAttribute("exams",exams.countByActiveTrue());
        model.addAttribute("categories",categories.count());
        model.addAttribute("settings",SettingsForm.from(settings.current()));
        return "admin/dashboard";
    }
}
