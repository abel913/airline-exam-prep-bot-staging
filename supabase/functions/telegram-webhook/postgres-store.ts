import { PostgresDatabase, type QueryClient, type SettingsRow } from "./postgres-database.ts";
import {
  decodePhoneIdentityKey,
  hmacSha256Hex,
  normalizeEthiopianPhone,
  phoneIdentity,
} from "./domain.mjs";

type UserRow = {
  id: string | bigint;
  telegram_user_id: string | bigint;
  preferred_language: string | null;
  selected_exam_type_id: string | bigint | null;
  registration_status: string;
};

type ExamRow = { id: string | bigint; name: string; name_am: string };
type GrantRow = {
  access_level: string;
  practice_limit: number;
  mock_limit: number;
  questions_per_mock: number;
  practice_used: number;
  mocks_used: number;
};

export type RegistrationView = {
  status: "LANGUAGE_REQUIRED" | "EXAM_TYPE_REQUIRED" | "PHONE_REQUIRED" | "EXAM_SWITCH_REQUIRED" | "COMPLETED";
  language: "en" | "am";
  exams?: Array<{ id: string; name: string; nameAm: string; current?: boolean }>;
  errorKey?: string | null;
  grant?: {
    accessLevel: string;
    practiceLimit: number;
    mockLimit: number;
    questionsPerMock: number;
    practiceUsed: number;
    mocksUsed: number;
    activeMockId?: string | null;
    examName?: string;
    examNameAm?: string;
  };
  examName?: string;
  examNameAm?: string;
};

function stringId(value: string | bigint | null): string | null {
  return value === null ? null : String(value);
}

export class PostgresRegistrationStore {
  constructor(
    private readonly database: PostgresDatabase,
    private readonly configuredPhoneKey: () => string,
  ) {}

  private async transaction<T>(operation: (client: QueryClient, settings: SettingsRow) => Promise<T>): Promise<T> {
    return await this.database.transaction(operation);
  }

  private async findUser(client: QueryClient, telegramId: string): Promise<UserRow | null> {
    const result = await client.queryObject<UserRow>`
      SELECT id, telegram_user_id, preferred_language, selected_exam_type_id, registration_status
      FROM bot_users WHERE telegram_user_id = ${telegramId}
    `;
    return result.rows[0] ?? null;
  }

  private async activeExam(client: QueryClient, examId: string): Promise<ExamRow | null> {
    const result = await client.queryObject<ExamRow>`
      SELECT id, name, name_am FROM exam_types WHERE id = ${examId} AND active = TRUE
    `;
    return result.rows[0] ?? null;
  }

  private async loadView(
    client: QueryClient,
    user: UserRow | null,
    errorKey: string | null = null,
  ): Promise<RegistrationView> {
    if (!user) return { status: "LANGUAGE_REQUIRED", language: "en", exams: [], errorKey };
    let status = user.registration_status as RegistrationView["status"];
    let selectedExamId = stringId(user.selected_exam_type_id);
    if (status === "PHONE_REQUIRED" && selectedExamId
      && !(await this.activeExam(client, selectedExamId))) {
      await client.queryArray`
        UPDATE bot_users SET selected_exam_type_id = NULL, registration_status = 'EXAM_TYPE_REQUIRED', updated_at = CURRENT_TIMESTAMP
        WHERE id = ${String(user.id)}
      `;
      status = "EXAM_TYPE_REQUIRED";
      selectedExamId = null;
    }
    const language = user.preferred_language === "am" ? "am" : "en";
    let exams: RegistrationView["exams"] = [];
    let grant: RegistrationView["grant"];
    if (status === "EXAM_TYPE_REQUIRED" || status === "EXAM_SWITCH_REQUIRED") {
      const result = await client.queryObject<ExamRow>`
        SELECT id, name, name_am FROM exam_types WHERE active = TRUE ORDER BY display_order, id
      `;
      exams = result.rows.map((exam) => ({ id: String(exam.id), name: exam.name, nameAm: exam.name_am ?? "",
        current: String(exam.id) === selectedExamId }));
    } else if (status === "COMPLETED") {
      const result = await client.queryObject<GrantRow>`
        SELECT access_level, practice_limit, mock_limit, questions_per_mock, practice_used, mocks_used
        FROM access_entitlements WHERE user_id = ${String(user.id)} AND exam_type_id = ${selectedExamId}
      `;
      const found = result.rows[0];
      if (!found) throw new Error("COMPLETED_USER_WITHOUT_ENTITLEMENT");
      const selected = await client.queryObject<ExamRow>`SELECT id,name,name_am FROM exam_types WHERE id=${selectedExamId}`;
      grant = {
        accessLevel: found.access_level,
        practiceLimit: found.practice_limit,
        mockLimit: found.mock_limit,
        questionsPerMock: found.questions_per_mock,
        practiceUsed: found.practice_used,
        mocksUsed: found.mocks_used,
        examName: selected.rows[0]?.name ?? "",
        examNameAm: selected.rows[0]?.name_am ?? "",
      };
      const activeMock = await client.queryObject<{ id: string | bigint }>`
        SELECT id FROM mock_attempts WHERE active_user_id = ${String(user.id)} AND exam_type_id=${selectedExamId}
      `;
      grant.activeMockId = activeMock.rows[0] ? String(activeMock.rows[0].id) : null;
    }
    return { status, language, exams, errorKey, grant,
      examName: grant?.examName, examNameAm: grant?.examNameAm };
  }

  async start(telegramId: string): Promise<RegistrationView> {
    return this.transaction(async (client) => {
      let user = await this.findUser(client, telegramId);
      if (!user) {
        await client.queryArray`
          INSERT INTO bot_users (telegram_user_id, registration_status, created_at, updated_at)
          VALUES (${telegramId}, 'LANGUAGE_REQUIRED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        `;
        user = await this.findUser(client, telegramId);
      }
      return this.loadView(client, user);
    });
  }

  async language(telegramId: string, language: "en" | "am"): Promise<RegistrationView> {
    return this.transaction(async (client) => {
      const user = await this.findUser(client, telegramId);
      if (user?.registration_status === "LANGUAGE_REQUIRED") {
        await client.queryArray`
          UPDATE bot_users SET preferred_language = ${language}, registration_status = 'EXAM_TYPE_REQUIRED', updated_at = CURRENT_TIMESTAMP
          WHERE id = ${String(user.id)}
        `;
        return this.loadView(client, { ...user, preferred_language: language, registration_status: "EXAM_TYPE_REQUIRED" });
      }
      if (user?.registration_status === "COMPLETED") {
        await client.queryArray`
          UPDATE bot_users SET preferred_language = ${language}, updated_at = CURRENT_TIMESTAMP
          WHERE id = ${String(user.id)} AND registration_status = 'COMPLETED'
        `;
        return this.loadView(client, { ...user, preferred_language: language });
      }
      return this.loadView(client, user);
    });
  }

  async exam(telegramId: string, examId: string): Promise<RegistrationView> {
    return this.transaction(async (client) => {
      const user = await this.findUser(client, telegramId);
      if (!user || !["EXAM_TYPE_REQUIRED", "COMPLETED"].includes(user.registration_status)) return this.loadView(client, user);
      const exam = await this.activeExam(client, examId);
      if (!exam) return this.loadView(client, user, "registration.examUnavailable");
      if (user.registration_status === "COMPLETED") {
        const settings = (await client.queryObject<SettingsRow>`SELECT * FROM app_settings WHERE id=1 FOR UPDATE`).rows[0];
        await client.queryArray`
          INSERT INTO access_entitlements(user_id,exam_type_id,phone_identity_hash,access_level,practice_limit,mock_limit,
            questions_per_mock,practice_used,mocks_used,grant_source,granted_at,created_at,updated_at)
          VALUES (${String(user.id)},${examId},(SELECT phone_identity_hash FROM bot_users WHERE id=${String(user.id)}),
            'FREE',${settings.free_practice_limit},${settings.free_mock_limit},${settings.questions_per_mock},0,0,
            'EXAM_ACTIVATION',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
          ON CONFLICT (user_id,exam_type_id) DO NOTHING
        `;
        await client.queryArray`UPDATE bot_users SET selected_exam_type_id=${examId},updated_at=CURRENT_TIMESTAMP WHERE id=${String(user.id)}`;
        return this.loadView(client, { ...user, selected_exam_type_id: examId });
      }
      await client.queryArray`
        UPDATE bot_users SET selected_exam_type_id = ${examId}, registration_status = 'PHONE_REQUIRED', updated_at = CURRENT_TIMESTAMP
        WHERE id = ${String(user.id)}
      `;
      return this.loadView(client, { ...user, selected_exam_type_id: examId, registration_status: "PHONE_REQUIRED" });
    });
  }

  async switchExams(telegramId: string): Promise<RegistrationView> {
    return this.transaction(async (client) => {
      const user = await this.findUser(client, telegramId);
      if (!user || user.registration_status !== "COMPLETED") return this.loadView(client, user);
      const view = await this.loadView(client, user);
      const exams = await client.queryObject<ExamRow>`SELECT id,name,name_am FROM exam_types WHERE active=TRUE ORDER BY display_order,id`;
      return { ...view, status: "EXAM_SWITCH_REQUIRED", exams: exams.rows.map((exam) => ({
        id:String(exam.id),name:exam.name,nameAm:exam.name_am??"",current:String(exam.id)===String(user.selected_exam_type_id),
      })) };
    });
  }

  async manualPhoneInput(telegramId: string): Promise<RegistrationView | null> {
    return await this.database.withConnection(async (client) => {
      const user = await this.findUser(client, telegramId);
      if (!user || user.registration_status !== "PHONE_REQUIRED") return null;
      return {
        status: "PHONE_REQUIRED",
        language: user.preferred_language === "am" ? "am" : "en",
        exams: [],
        errorKey: "registration.manualPhone",
      };
    });
  }

  async contact(telegramId: string, contactOwner: string | null, rawPhone: string | null): Promise<RegistrationView> {
    if (contactOwner === null || contactOwner !== telegramId) {
      return await this.database.transaction(async (client) => this.loadView(client, await this.findUser(client, telegramId), "registration.ownContact"));
    }
    return await this.savePhone(telegramId, rawPhone, true);
  }

  async manualPhone(telegramId: string, rawPhone: string): Promise<RegistrationView> {
    return await this.savePhone(telegramId, rawPhone, false);
  }

  private async savePhone(telegramId: string, rawPhone: string | null, verifiedContact: boolean): Promise<RegistrationView> {
    return this.transaction(async (client, settings) => {
      const user = await this.findUser(client, telegramId);
      if (!user || (user.registration_status !== "PHONE_REQUIRED" && !(verifiedContact && user.registration_status === "COMPLETED"))) return this.loadView(client, user);

      let phoneKey: Uint8Array;
      let fingerprint: string;
      try {
        phoneKey = decodePhoneIdentityKey(this.configuredPhoneKey());
        fingerprint = await hmacSha256Hex(phoneKey, "airline-exam-phone-key-v1");
      } catch {
        return this.loadView(client, user, "registration.unavailable");
      }
      if (settings.phone_key_fingerprint !== null && settings.phone_key_fingerprint !== fingerprint) {
        return this.loadView(client, user, "registration.unavailable");
      }

      const selectedExam = stringId(user.selected_exam_type_id);
      if (!selectedExam || !(await this.activeExam(client, selectedExam))) {
        await client.queryArray`
          UPDATE bot_users SET selected_exam_type_id = NULL, registration_status = 'EXAM_TYPE_REQUIRED', updated_at = CURRENT_TIMESTAMP
          WHERE id = ${String(user.id)}
        `;
        return this.loadView(client, { ...user, selected_exam_type_id: null, registration_status: "EXAM_TYPE_REQUIRED" }, "registration.examUnavailable");
      }

      let canonicalPhone: string;
      try {
        canonicalPhone = normalizeEthiopianPhone(rawPhone);
      } catch {
        return this.loadView(client, user, "registration.invalidPhone");
      }
      const identityHash = await phoneIdentity(phoneKey, canonicalPhone);
      const existing = await client.queryObject<{ id: string | bigint }>`
        SELECT id FROM bot_users WHERE phone_identity_hash = ${identityHash}
      `;
      if (existing.rows.some((row) => String(row.id) !== String(user.id))) return this.loadView(client, user, "registration.duplicatePhone");
      if (user.registration_status === "COMPLETED") {
        const own = await client.queryObject<{phone_identity_hash:string|null}>`SELECT phone_identity_hash FROM bot_users WHERE id=${String(user.id)}`;
        if (own.rows[0]?.phone_identity_hash !== identityHash) return this.loadView(client, user, "registration.duplicatePhone");
        await client.queryArray`UPDATE bot_users SET phone_e164=${canonicalPhone},phone_verification_status='VERIFIED_TELEGRAM_CONTACT',updated_at=CURRENT_TIMESTAMP WHERE id=${String(user.id)}`;
        return this.loadView(client, user);
      }

      await client.queryArray`
        UPDATE app_settings SET phone_key_fingerprint = ${fingerprint}, updated_at = CURRENT_TIMESTAMP WHERE id = 1
      `;
      await client.queryArray`
        UPDATE bot_users SET phone_identity_hash = ${identityHash}, phone_e164=${canonicalPhone},
          phone_verification_status=${verifiedContact ? "VERIFIED_TELEGRAM_CONTACT" : "UNVERIFIED_TYPED"}, registration_completed_at = CURRENT_TIMESTAMP,
          registration_status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP WHERE id = ${String(user.id)}
      `;
      await client.queryArray`
        INSERT INTO access_entitlements (
          user_id, exam_type_id, phone_identity_hash, access_level, practice_limit, mock_limit, questions_per_mock,
          practice_used, mocks_used, grant_source, granted_at, created_at, updated_at
        ) VALUES (
          ${String(user.id)}, ${selectedExam}, ${identityHash}, 'FREE', ${settings.free_practice_limit}, ${settings.free_mock_limit},
          ${settings.questions_per_mock}, 0, 0, 'REGISTRATION', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        )
      `;
      return this.loadView(client, { ...user, registration_status: "COMPLETED" });
    });
  }

  async current(telegramId: string): Promise<RegistrationView | null> {
    return this.transaction(async (client) => {
      const user = await this.findUser(client, telegramId);
      return user ? this.loadView(client, user) : null;
    });
  }

  async healthCheck(): Promise<void> {
    return await this.database.healthCheck();
  }
}
