package com.airlineprep.bot.admin;

import java.math.BigDecimal;
import com.airlineprep.bot.IsolatedDatabaseSupport;
import com.airlineprep.bot.audit.AdminChangeRepository;
import com.airlineprep.bot.settings.*;
import com.airlineprep.bot.examtype.ExamTypeRepository;
import com.airlineprep.bot.category.CategoryRepository;
import com.airlineprep.bot.user.RegistrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class AdminWebTests extends IsolatedDatabaseSupport {
    @Autowired MockMvc mvc;
    @Autowired AdminUserRepository admins;
    @Autowired PasswordEncoder encoder;
    @Autowired SettingsService settings;
    @Autowired CatalogService catalog;
    @Autowired ExamTypeRepository exams;
    @Autowired CategoryRepository categories;
    @Autowired AdminChangeRepository changes;
    @Autowired RegistrationService registration;
    static final String PASSWORD = "unit-test-password-only-123";
    @BeforeEach void admin() {
        AdminUser user = new AdminUser(); user.setUsername("test-admin");
        user.setPasswordHash(encoder.encode(PASSWORD)); user.setEnabled(true); admins.saveAndFlush(user);
    }
    @Test void persistedAdminLoginWorksAndStoresOnlyBCrypt() throws Exception {
        String stored = admins.findByUsername("test-admin").orElseThrow().getPasswordHash();
        assertThat(stored).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD,stored)).isTrue();
        mvc.perform(post("/admin/login").with(csrf()).param("username","test-admin").param("password",PASSWORD))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin")).andExpect(authenticated().withUsername("test-admin"));
    }
    @Test void invalidLoginUsesGenericFailure() throws Exception {
        mvc.perform(post("/admin/login").with(csrf()).param("username","missing").param("password","wrong"))
            .andExpect(redirectedUrl("/admin/login?error")).andExpect(unauthenticated());
        mvc.perform(get("/admin/login?error")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Unable to sign in.")));
    }
    @Test void loginAndCssArePublic() throws Exception {
        mvc.perform(get("/admin/login")).andExpect(status().isOk());
        mvc.perform(get("/assets/admin.css")).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"/admin","/admin/settings","/admin/settings/study-reminders","/admin/exam-types","/admin/categories","/admin/users"})
    void adminPagesRequireLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/admin/login"));
    }
    @ParameterizedTest @ValueSource(strings={"/admin","/admin/settings","/admin/settings/study-reminders","/admin/exam-types","/admin/categories",
        "/admin/exam-types/new","/admin/categories/new"})
    void authenticatedPagesRender(String path) throws Exception {
        mvc.perform(get(path).with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk());
    }
    @Test void healthRemainsPublicAndOtherActuatorEndpointsDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}"));
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }
    @ParameterizedTest @ValueSource(strings={"/admin/settings","/admin/settings/study-reminders","/admin/exam-types/new","/admin/categories/new","/admin/users/1/remove","/admin/logout"})
    void csrfProtectsEveryMutation(String path) throws Exception {
        mvc.perform(post(path).with(user("test-admin").roles("ADMIN"))).andExpect(status().isForbidden());
    }
    @Test void csrfDoesNotReplaceAuthenticationOrAdminRole() throws Exception {
        mvc.perform(post("/admin/settings").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin").with(user("ordinary").roles("USER"))).andExpect(status().isForbidden());
    }
    @Test void logoutInvalidatesAuthentication() throws Exception {
        mvc.perform(post("/admin/logout").with(csrf()).with(user("test-admin").roles("ADMIN")))
            .andExpect(redirectedUrl("/admin/login?logout")).andExpect(unauthenticated());
    }
    @Test void settingsValidationRejectsInvalidValues() throws Exception {
        mvc.perform(post("/admin/settings").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("freePracticeLimit","-1").param("freeMockLimit","-1").param("questionsPerMock","0")
            .param("lifetimePrice","-10").param("currency","bad!").param("supportInfo",""))
            .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form",
                "freePracticeLimit","freeMockLimit","questionsPerMock","lifetimePrice","currency"));
        assertThat(settings.current().getFreePracticeLimit()).isEqualTo(100);
    }
    @Test void adminSettingsUpdatePreservesOldSnapshotsAndAuditsActor() throws Exception {
        Long exam = catalog.save(false,null,new CatalogForm("pilot","Pilot","",true,0,null),"test-admin");
        complete(71,exam,"0911111111");
        mvc.perform(post("/admin/settings").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("freePracticeLimit","20").param("freeMockLimit","1").param("questionsPerMock","30")
            .param("lifetimePrice","60.00").param("currency","ETB").param("supportInfo","Contact support"))
            .andExpect(redirectedUrl("/admin/settings?saved"));
        complete(72,exam,"0711111111");
        assertThat(registration.start(71).practiceLimit()).isEqualTo(100);
        assertThat(registration.start(71).mockLimit()).isEqualTo(2);
        assertThat(registration.start(71).questionsPerMock()).isEqualTo(50);
        assertThat(registration.start(72).practiceLimit()).isEqualTo(20);
        assertThat(registration.start(72).mockLimit()).isEqualTo(1);
        assertThat(registration.start(72).questionsPerMock()).isEqualTo(30);
        assertThat(changes.findAll()).anyMatch(c -> c.getActor().equals("test-admin") && c.getAction().equals("SETTINGS_UPDATED"));
    }
    void complete(long sender, long exam, String phone) {
        registration.start(sender); registration.language(sender,"en"); registration.exam(sender,exam);
        registration.contact(sender,sender,phone);
    }
    @Test void examAndCategoryCreateEditToggleWorkWithoutDeletingReferences() throws Exception {
        mvc.perform(post("/admin/exam-types/new").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("code","cabin").param("name","Cabin Crew").param("nameAm","").param("active","true").param("displayOrder","1"))
            .andExpect(redirectedUrl("/admin/exam-types?saved"));
        Long exam = exams.findAll().getFirst().getId();
        mvc.perform(get("/admin/exam-types/"+exam+"/edit").with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/admin/exam-types/"+exam+"/edit").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("code","cabin").param("name","Cabin Crew Updated").param("nameAm","").param("active","true").param("displayOrder","2"))
            .andExpect(status().is3xxRedirection());
        complete(81,exam,"0922222222");
        mvc.perform(post("/admin/exam-types/"+exam+"/toggle").with(csrf()).with(user("test-admin").roles("ADMIN")))
            .andExpect(status().is3xxRedirection());
        assertThat(exams.findById(exam).orElseThrow().getActive()).isFalse();
        assertThat(registration.start(81).practiceLimit()).isEqualTo(100);
        registration.start(82); assertThat(registration.language(82,"en").exams()).isEmpty();
        mvc.perform(post("/admin/exam-types/"+exam+"/toggle").with(csrf()).with(user("test-admin").roles("ADMIN")))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/categories/new").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("examTypeId",exam.toString()).param("code","english").param("name","English").param("nameAm","")
            .param("active","true").param("displayOrder","0")).andExpect(redirectedUrl("/admin/categories?saved"));
        Long category = categories.findAll().getFirst().getId();
        mvc.perform(get("/admin/categories/"+category+"/edit").with(user("test-admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/admin/categories/"+category+"/edit").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("examTypeId",exam.toString()).param("code","english").param("name","English Updated").param("nameAm","")
            .param("active","true").param("displayOrder","1")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/categories/"+category+"/toggle").with(csrf()).with(user("test-admin").roles("ADMIN")))
            .andExpect(status().is3xxRedirection());
        assertThat(categories.findById(category).orElseThrow().getActive()).isFalse();
        assertThat(categories.findById(category).orElseThrow().getName()).isEqualTo("English Updated");
    }
    @Test void catalogValidationAndDuplicateCodesAreSafe() throws Exception {
        mvc.perform(post("/admin/exam-types/new").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("code","BAD!").param("name"," ").param("nameAm","").param("displayOrder","-1"))
            .andExpect(model().attributeHasFieldErrors("form","code","name","displayOrder"));
        catalog.save(false,null,new CatalogForm("pilot","Pilot","",true,0,null),"test-admin");
        mvc.perform(post("/admin/exam-types/new").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("code","pilot").param("name","Pilot duplicate").param("nameAm","").param("displayOrder","0"))
            .andExpect(model().attributeHasErrors("form"));
        mvc.perform(post("/admin/categories/new").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("examTypeId","999999").param("code","math").param("name","Math").param("nameAm","").param("displayOrder","0"))
            .andExpect(model().attributeHasErrors("form"));
        assertThat(categories.count()).isZero();
    }
    @Test void serviceLayerAlsoValidatesSettings() {
        assertThatThrownBy(() -> settings.update(new SettingsForm(-1,2,0,new BigDecimal("-1"),"ETB",false,false,""),"test-admin"))
            .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
    }
    @Test void studyReminderCampaignIsAdminOnlyValidatedAuditedAndShowsAllStatuses() throws Exception {
        var now=java.time.LocalDateTime.now(com.airlineprep.bot.settings.StudyReminderCampaignForm.DISPLAY_ZONE)
            .withSecond(0).withNano(0);
        var activeStart=now.minusHours(1).toString();
        var activeEnd=now.plusHours(1).toString();
        var scheduledStart=now.plusDays(1).toString();
        var scheduledEnd=now.plusDays(2).toString();
        var expiredStart=now.minusDays(3).toString();
        var expiredEnd=now.minusDays(2).toString();
        mvc.perform(get("/admin/settings/study-reminders").with(user("ordinary").roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/settings/study-reminders").with(csrf()).with(user("ordinary").roles("USER"))
            .param("enabled","true").param("startAt","2026-10-10T00:00").param("endAt","2026-10-11T00:00"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/admin/settings/study-reminders").with(user("test-admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(model().attribute("campaignStatus","OFF"));
        mvc.perform(post("/admin/settings/study-reminders").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("enabled","true").param("startAt","2026-10-11T00:00").param("endAt","2026-10-10T00:00"))
            .andExpect(status().isOk()).andExpect(model().attribute("error","The end time must be after the start time."));
        assertThat(settings.current().isStudyRemindersGloballyEnabled()).isFalse();

        mvc.perform(post("/admin/settings/study-reminders").with(csrf()).with(user("test-admin").roles("ADMIN"))
            .param("enabled","true").param("startAt",activeStart).param("endAt",activeEnd))
            .andExpect(status().is3xxRedirection());
        assertThat(settings.current().isStudyRemindersGloballyEnabled()).isTrue();
        assertThat(settings.current().getStudyRemindersUpdatedBy()).isEqualTo("test-admin");
        assertThat(changes.findAll()).anyMatch(c -> c.getActor().equals("test-admin")
            && c.getAction().equals("STUDY_REMINDER_CAMPAIGN_UPDATED"));
        mvc.perform(get("/admin/settings/study-reminders").with(user("test-admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(model().attribute("campaignStatus","ACTIVE"));

        settings.updateStudyReminderCampaign(new StudyReminderCampaignForm(true,scheduledStart,scheduledEnd),"test-admin");
        mvc.perform(get("/admin/settings/study-reminders").with(user("test-admin").roles("ADMIN")))
            .andExpect(model().attribute("campaignStatus","SCHEDULED"));
        settings.updateStudyReminderCampaign(new StudyReminderCampaignForm(true,expiredStart,expiredEnd),"test-admin");
        mvc.perform(get("/admin/settings/study-reminders").with(user("test-admin").roles("ADMIN")))
            .andExpect(model().attribute("campaignStatus","EXPIRED"));
        settings.updateStudyReminderCampaign(new StudyReminderCampaignForm(false,expiredStart,expiredEnd),"test-admin");
        assertThat(settings.current().getStudyRemindersStartAt()).isNotNull();
        assertThat(settings.current().isStudyRemindersGloballyEnabled()).isFalse();
    }
}
