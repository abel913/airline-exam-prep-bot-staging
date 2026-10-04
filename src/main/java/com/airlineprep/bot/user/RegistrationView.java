package com.airlineprep.bot.user;

import java.util.List;

public record RegistrationView(RegistrationStatus status, String language, List<ExamOption> exams,
        String errorKey, Integer practiceLimit, Integer mockLimit, Integer questionsPerMock) {
    public record ExamOption(Long id, String name, String nameAm, boolean current) {}
}
