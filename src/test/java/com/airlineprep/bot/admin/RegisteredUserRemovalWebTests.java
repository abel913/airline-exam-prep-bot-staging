package com.airlineprep.bot.admin;

import com.airlineprep.bot.IsolatedDatabaseSupport;
import com.airlineprep.bot.audit.AdminChangeRepository;
import com.airlineprep.bot.user.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class RegisteredUserRemovalWebTests extends IsolatedDatabaseSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogService catalog;
    @Autowired RegistrationService registration;
    @Autowired RegisteredUserRemovalService removal;
    @Autowired AdminChangeRepository changes;

    @Test
    void removalIsAdminOnlyPostCsrfProtectedAndRequiresTypedConfirmation() throws Exception {
        long exam = exam("remove-web");
        long telegramId = 2_400_100_101L;
        long userId = complete(telegramId, exam, "0912345101");
        String hash = jdbc.queryForObject("SELECT phone_identity_hash FROM bot_users WHERE id=?", String.class, userId);

        mvc.perform(get("/admin/users/" + userId + "/remove"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/users/" + userId + "/remove").with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
        mvc.perform(post("/admin/users/" + userId + "/remove").with(user("student").roles("USER")).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/" + userId + "/remove").with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden());

        mvc.perform(get("/admin/users/" + userId + "/remove").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(view().name("admin/user-remove-confirm"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Type REMOVE")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Not stored by this application")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("0912345101"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(hash))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?", Integer.class, userId)).isEqualTo(1);

        mvc.perform(post("/admin/users/" + userId + "/remove").with(user("admin").roles("ADMIN")).with(csrf())
                .param("confirmation", "remove"))
            .andExpect(status().isOk()).andExpect(view().name("admin/user-remove-confirm"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Type REMOVE exactly")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?", Integer.class, userId)).isEqualTo(1);

        mvc.perform(get("/admin/users").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Remove User")));
        mvc.perform(post("/admin/users/" + userId + "/remove").with(user("admin").roles("ADMIN")).with(csrf())
                .param("confirmation", "REMOVE"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/users"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_types WHERE id=?", Integer.class, exam)).isEqualTo(1);
        assertThat(changes.findAll()).anyMatch(change -> change.getActor().equals("admin")
            && change.getAction().equals("REGISTERED_USER_REMOVED") && change.getTarget().equals("bot_user:" + userId));
    }

    @Test
    void nonexistentUserHasSafeResultAndCannotAffectAnotherUser() throws Exception {
        long exam = exam("remove-invalid");
        long userId = complete(2_400_100_102L, exam, "0912345102");
        mvc.perform(post("/admin/users/999999999/remove").with(user("admin").roles("ADMIN")).with(csrf())
                .param("confirmation", "REMOVE"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/users"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bot_users WHERE id=?", Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_entitlements WHERE user_id=?", Integer.class, userId)).isEqualTo(1);
        assertThat(removal.remove(999999999L, "admin")).isEmpty();
    }

    private long exam(String code) {
        return catalog.save(false, null, new CatalogForm(code, "Synthetic Removal Test", "", true,
            Math.toIntExact(jdbc.queryForObject("SELECT COUNT(*) FROM exam_types", Long.class)), null), "remove-web-test");
    }

    private long complete(long telegramId, long examId, String phone) {
        registration.start(telegramId);
        registration.language(telegramId, "en");
        registration.exam(telegramId, examId);
        registration.contact(telegramId, telegramId, phone);
        return jdbc.queryForObject("SELECT id FROM bot_users WHERE telegram_user_id=?", Long.class, telegramId);
    }
}
