package com.airlineprep.bot.settings;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

public record StudyReminderCampaignForm(boolean enabled, String startAt, String endAt) {
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Africa/Addis_Ababa");
    private static final DateTimeFormatter FORM_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT);

    public static StudyReminderCampaignForm from(AppSettings settings) {
        return new StudyReminderCampaignForm(settings.isStudyRemindersGloballyEnabled(),
            format(settings.getStudyRemindersStartAt()), format(settings.getStudyRemindersEndAt()));
    }

    public String validationError() {
        if (!enabled && (startAt == null || startAt.isBlank()) && (endAt == null || endAt.isBlank())) return null;
        if (startAt == null || startAt.isBlank() || endAt == null || endAt.isBlank())
            return "Enter both the campaign start and end times.";
        try {
            var start=LocalDateTime.parse(startAt,FORM_FORMAT);
            var end=LocalDateTime.parse(endAt,FORM_FORMAT);
            if(start.getYear()<2000||start.getYear()>2200||end.getYear()<2000||end.getYear()>2200)
                return "Campaign dates must be between 2000 and 2200.";
            if (!toInstant(startAt).isBefore(toInstant(endAt))) return "The end time must be after the start time.";
        } catch (RuntimeException invalid) {
            return "Enter valid start and end times.";
        }
        return null;
    }

    public Instant startInstant() { return toInstant(startAt); }
    public Instant endInstant() { return toInstant(endAt); }

    private static Instant toInstant(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDateTime.parse(value, FORM_FORMAT).atZone(DISPLAY_ZONE).toInstant();
    }

    private static String format(Instant value) {
        return value == null ? "" : FORM_FORMAT.format(value.atZone(DISPLAY_ZONE));
    }
}
