import { PostgresDatabase, type QueryClient } from "./postgres-database.ts";

export type PracticeStudent = {
  id: string;
  telegramId: string;
  examId: string;
  language: "en" | "am";
  accessLevel: "FREE" | "LIFETIME";
  practiceLimit: number;
  practiceUsed: number;
  mockLimit: number;
  mocksUsed: number;
  questionsPerMock: number;
};

export type PracticeDelivery = {
  id: string;
  userId: string;
  questionId: string;
  versionId: string;
  categoryFilter: string | null;
  nextDeliveryId: string | null;
  reviewDeliveryId: string | null;
  selectedOption: number | null;
  answeredAt: string | null;
};

export type FrozenQuestion = {
  id: string;
  versionId: string;
  examId: string;
  categoryId: string;
  categoryName: string;
  text: string;
  explanation: string;
  options: Array<{ position: number; text: string; correct: boolean }>;
};

function stringId(value: string | bigint | number | null): string | null {
  return value === null ? null : String(value);
}

function readDelivery(row: Record<string, unknown>): PracticeDelivery {
  return {
    id: String(row.id),
    userId: String(row.user_id),
    questionId: String(row.question_id),
    versionId: String(row.version_id),
    categoryFilter: stringId(row.category_filter as string | bigint | number | null),
    nextDeliveryId: stringId(row.next_delivery_id as string | bigint | number | null),
    reviewDeliveryId: stringId(row.review_delivery_id as string | bigint | number | null),
    selectedOption: row.selected_option === null ? null : Number(row.selected_option),
    answeredAt: row.answered_at === null ? null : String(row.answered_at),
  };
}

export class PracticeUnitOfWork {
  constructor(private readonly client: QueryClient) {}

  async student(telegramId: string): Promise<PracticeStudent | null> {
    const users = await this.client.queryObject<{
      id: string | bigint; telegram_user_id: string | bigint; preferred_language: string | null;
      selected_exam_type_id: string | bigint | null; registration_status: string;
    }>`
      SELECT id, telegram_user_id, preferred_language, selected_exam_type_id, registration_status
      FROM bot_users WHERE telegram_user_id = ${telegramId}
    `;
    const user = users.rows[0];
    if (!user || user.registration_status !== "COMPLETED" || user.selected_exam_type_id === null) return null;
    const grants = await this.client.queryObject<{
      access_level: string; practice_limit: number; practice_used: number; mock_limit: number;
      mocks_used: number; questions_per_mock: number;
    }>`
      SELECT access_level, practice_limit, practice_used, mock_limit, mocks_used, questions_per_mock
      FROM access_entitlements WHERE user_id = ${String(user.id)} AND exam_type_id = ${String(user.selected_exam_type_id)}
    `;
    const grant = grants.rows[0];
    if (!grant || !["FREE", "LIFETIME"].includes(grant.access_level)) return null;
    return {
      id: String(user.id), telegramId: String(user.telegram_user_id), examId: String(user.selected_exam_type_id),
      language: user.preferred_language === "am" ? "am" : "en", accessLevel: grant.access_level as PracticeStudent["accessLevel"],
      practiceLimit: Number(grant.practice_limit), practiceUsed: Number(grant.practice_used),
      mockLimit: Number(grant.mock_limit), mocksUsed: Number(grant.mocks_used),
      questionsPerMock: Number(grant.questions_per_mock),
    };
  }

  async categories(student: PracticeStudent, page: number): Promise<Array<{ id: string; name: string; nameAm: string }>> {
    const rows = await this.client.queryObject<{ id: string | bigint; name: string; name_am: string }>`
      SELECT DISTINCT c.id, c.name, c.name_am
      FROM questions q JOIN question_versions v ON v.id = q.current_version_id
      JOIN exam_types e ON e.id = v.exam_type_id
      JOIN categories c ON c.id = v.category_id AND c.exam_type_id = e.id
      WHERE q.status = 'PUBLISHED' AND e.active = TRUE AND c.active = TRUE AND e.id = ${student.examId}
        AND (v.free_pool = TRUE OR (${student.accessLevel === "LIFETIME"} = TRUE AND v.premium_pool = TRUE))
      ORDER BY c.id LIMIT 21 OFFSET ${page * 20}
    `;
    return rows.rows.map((row) => ({ id: String(row.id), name: row.name, nameAm: row.name_am ?? "" }));
  }

  async validateCategory(student: PracticeStudent, categoryId: string | null): Promise<void> {
    if (categoryId === null) return;
    const result = await this.client.queryObject<{ id: string | bigint }>`
      SELECT id FROM categories WHERE id = ${categoryId} AND exam_type_id = ${student.examId} AND active = TRUE
    `;
    if (!result.rows.length) throw new Error("STUDENT_INVALID");
  }

  async ownedDelivery(student: PracticeStudent, id: string): Promise<PracticeDelivery | null> {
    const result = await this.client.queryObject<Record<string, unknown>>`
      SELECT * FROM practice_deliveries WHERE id = ${id} AND user_id = ${student.id}
    `;
    return result.rows[0] ? readDelivery(result.rows[0]) : null;
  }

  async deliveryByUpdateId(updateId: string): Promise<PracticeDelivery | null> {
    const result = await this.client.queryObject<Record<string, unknown>>`
      SELECT d.* FROM practice_update_receipts r
      JOIN practice_deliveries d ON d.id = r.delivery_id AND d.user_id = r.user_id AND d.question_id = r.question_id
      WHERE r.update_id = ${updateId}
    `;
    return result.rows[0] ? readDelivery(result.rows[0]) : null;
  }

  async recordDeliveryUpdate(student: PracticeStudent, updateId: string, delivery: PracticeDelivery): Promise<void> {
    await this.client.queryArray`
      INSERT INTO practice_update_receipts (update_id, user_id, question_id, delivery_id, created_at)
      VALUES (${updateId}, ${student.id}, ${delivery.questionId}, ${delivery.id}, CURRENT_TIMESTAMP)
    `;
  }

  async currentDelivery(student: PracticeStudent): Promise<PracticeDelivery | null> {
    const result = await this.client.queryObject<Record<string, unknown>>`
      SELECT d.* FROM practice_sessions p JOIN practice_deliveries d ON d.id = p.current_delivery_id
      WHERE p.user_id = ${student.id} AND p.exam_type_id = ${student.examId}
    `;
    return result.rows[0] ? readDelivery(result.rows[0]) : null;
  }

  async used(student: PracticeStudent, questionId: string): Promise<boolean> {
    const result = await this.client.queryObject<{ found: boolean }>`
      SELECT EXISTS(SELECT 1 FROM practice_usage WHERE user_id = ${student.id} AND exam_type_id = ${student.examId} AND question_id = ${questionId}) AS found
    `;
    return result.rows[0]?.found === true;
  }

  async selectVersion(student: PracticeStudent, categoryId: string | null, review: boolean): Promise<string | null> {
    // Keep the optional predicate static to retain parameterized SQL.
    const result = categoryId === null
      ? await this.client.queryObject<{ id: string | bigint }>`
        SELECT v.id FROM questions q JOIN question_versions v ON v.id = q.current_version_id
        JOIN exam_types e ON e.id = v.exam_type_id
        JOIN categories c ON c.id = v.category_id AND c.exam_type_id = e.id
        WHERE q.status = 'PUBLISHED' AND e.active = TRUE AND c.active = TRUE AND e.id = ${student.examId}
          AND (v.free_pool = TRUE OR (${student.accessLevel === "LIFETIME"} = TRUE AND v.premium_pool = TRUE))
          AND ${review} = EXISTS(SELECT 1 FROM practice_usage u WHERE u.user_id = ${student.id} AND u.exam_type_id = ${student.examId} AND u.question_id = q.id)
        ORDER BY COALESCE((SELECT MAX(d.id) FROM practice_deliveries d
          WHERE d.user_id = ${student.id} AND d.exam_type_id = ${student.examId} AND d.question_id = q.id), 0), q.id LIMIT 1
      `
      : await this.client.queryObject<{ id: string | bigint }>`
        SELECT v.id FROM questions q JOIN question_versions v ON v.id = q.current_version_id
        JOIN exam_types e ON e.id = v.exam_type_id
        JOIN categories c ON c.id = v.category_id AND c.exam_type_id = e.id
        WHERE q.status = 'PUBLISHED' AND e.active = TRUE AND c.active = TRUE AND e.id = ${student.examId}
          AND c.id = ${categoryId}
          AND (v.free_pool = TRUE OR (${student.accessLevel === "LIFETIME"} = TRUE AND v.premium_pool = TRUE))
          AND ${review} = EXISTS(SELECT 1 FROM practice_usage u WHERE u.user_id = ${student.id} AND u.exam_type_id = ${student.examId} AND u.question_id = q.id)
        ORDER BY COALESCE((SELECT MAX(d.id) FROM practice_deliveries d
          WHERE d.user_id = ${student.id} AND d.exam_type_id = ${student.examId} AND d.question_id = q.id), 0), q.id LIMIT 1
      `;
    return result.rows[0] ? String(result.rows[0].id) : null;
  }

  async frozen(versionId: string): Promise<FrozenQuestion | null> {
    const rows = await this.client.queryObject<{
      question_id: string | bigint; id: string | bigint; exam_type_id: string | bigint;
      category_id: string | bigint; category_name: string; question_text: string; explanation: string;
    }>`
      SELECT question_id, id, exam_type_id, category_id, category_name, question_text, explanation
      FROM question_versions WHERE id = ${versionId}
    `;
    const row = rows.rows[0];
    if (!row) return null;
    const options = await this.client.queryObject<{ position: number; option_text: string; correct: boolean }>`
      SELECT position, option_text, correct FROM question_options WHERE version_id = ${versionId} ORDER BY position
    `;
    return {
      id: String(row.question_id), versionId: String(row.id), examId: String(row.exam_type_id),
      categoryId: String(row.category_id), categoryName: row.category_name, text: row.question_text,
      explanation: row.explanation,
      options: options.rows.map((option) => ({ position: Number(option.position), text: option.option_text, correct: option.correct })),
    };
  }

  async createDelivery(student: PracticeStudent, versionId: string, categoryId: string | null): Promise<PracticeDelivery> {
    const version = await this.client.queryObject<{ question_id: string | bigint }>`
      SELECT question_id FROM question_versions WHERE id = ${versionId}
    `;
    const questionId = version.rows[0] ? String(version.rows[0].question_id) : null;
    if (questionId === null) throw new Error("STUDENT_INVALID");
    const inserted = await this.client.queryObject<Record<string, unknown>>`
      INSERT INTO practice_deliveries (user_id, exam_type_id, question_id, version_id, category_filter, created_at)
      VALUES (${student.id}, ${student.examId}, ${questionId}, ${versionId}, ${categoryId}, CURRENT_TIMESTAMP)
      RETURNING *
    `;
    const delivery = inserted.rows[0];
    if (!delivery) throw new Error("PRACTICE_DELIVERY_CREATE_FAILED");
    return readDelivery(delivery);
  }

  async link(previous: PracticeDelivery, nextId: string, review: boolean): Promise<void> {
    if (review) {
      await this.client.queryArray`UPDATE practice_deliveries SET review_delivery_id = ${nextId} WHERE id = ${previous.id} AND user_id = ${previous.userId}`;
    } else {
      await this.client.queryArray`UPDATE practice_deliveries SET next_delivery_id = ${nextId} WHERE id = ${previous.id} AND user_id = ${previous.userId}`;
    }
  }

  async setCurrentDelivery(student: PracticeStudent, id: string): Promise<void> {
    await this.client.queryArray`
      INSERT INTO practice_sessions (user_id, exam_type_id, current_delivery_id) VALUES (${student.id}, ${student.examId}, ${id})
      ON CONFLICT (user_id) DO UPDATE SET exam_type_id = EXCLUDED.exam_type_id, current_delivery_id = EXCLUDED.current_delivery_id
    `;
  }

  async recordAnswer(student: PracticeStudent, delivery: PracticeDelivery, option: number): Promise<{ usageCreated: boolean; practiceUsed: number }> {
    const updated = await this.client.queryObject<{ selected_option: number | null }>`
      UPDATE practice_deliveries SET selected_option = ${option}, answered_at = CURRENT_TIMESTAMP
      WHERE id = ${delivery.id} AND user_id = ${student.id} AND selected_option IS NULL
      RETURNING selected_option
    `;
    if (!updated.rows.length) return { usageCreated: false, practiceUsed: student.practiceUsed };
    const usage = await this.client.queryObject<{ question_id: string | bigint }>`
      INSERT INTO practice_usage (user_id, exam_type_id, question_id, first_delivery_id, created_at)
      VALUES (${student.id}, ${student.examId}, ${delivery.questionId}, ${delivery.id}, CURRENT_TIMESTAMP)
      ON CONFLICT (user_id, exam_type_id, question_id) DO NOTHING
      RETURNING question_id
    `;
    if (!usage.rows.length || student.accessLevel === "LIFETIME") {
      return { usageCreated: usage.rows.length > 0, practiceUsed: student.practiceUsed };
    }
    const counter = await this.client.queryObject<{ practice_used: number }>`
      UPDATE access_entitlements SET practice_used = practice_used + 1, updated_at = CURRENT_TIMESTAMP
      WHERE user_id = ${student.id} AND exam_type_id = ${student.examId} AND access_level = 'FREE' RETURNING practice_used
    `;
    if (!counter.rows.length) throw new Error("PRACTICE_ENTITLEMENT_UPDATE_FAILED");
    return { usageCreated: true, practiceUsed: Number(counter.rows[0].practice_used) };
  }

  async progress(student: PracticeStudent): Promise<{ answered: number; correct: number; categories: Array<{ id: string; name: string; answered: number; correct: number }>; completed: number; recent: Array<{ id: string; status: string; total: number; correct: number }> }> {
    const categories = await this.client.queryObject<{ category_id: string | bigint; category_name: string; answered: number | bigint; correct: number | bigint }>`
      SELECT v.category_id, v.category_name, COUNT(*) AS answered,
        SUM(CASE WHEN o.correct = TRUE THEN 1 ELSE 0 END) AS correct
      FROM practice_usage u JOIN practice_deliveries d ON d.id = u.first_delivery_id
      JOIN question_versions v ON v.id = d.version_id
      JOIN question_options o ON o.version_id = d.version_id AND o.position = d.selected_option
      WHERE u.user_id = ${student.id} AND v.exam_type_id = ${student.examId}
      GROUP BY v.category_id, v.category_name ORDER BY v.category_id, v.category_name
    `;
    const categoryRows = categories.rows.map((row) => ({ id: String(row.category_id), name: row.category_name, answered: Number(row.answered), correct: Number(row.correct) }));
    const answered = categoryRows.reduce((sum, row) => sum + row.answered, 0);
    const correct = categoryRows.reduce((sum, row) => sum + row.correct, 0);
    const completed = await this.client.queryObject<{ count: number | bigint }>`
      SELECT COUNT(*) AS count FROM mock_attempts WHERE user_id = ${student.id} AND exam_type_id = ${student.examId}
        AND status IN ('SUBMITTED', 'EXPIRED') AND first_answer_at IS NOT NULL
    `;
    const recent = await this.client.queryObject<{ id: string | bigint; status: string; question_count: number; correct_count: number }>`
      SELECT id, status, question_count, correct_count FROM mock_attempts
      WHERE user_id = ${student.id} AND exam_type_id = ${student.examId}
        AND status IN ('SUBMITTED', 'EXPIRED') AND first_answer_at IS NOT NULL
      ORDER BY id DESC LIMIT 10
    `;
    return {
      answered, correct, categories: categoryRows, completed: Number(completed.rows[0]?.count ?? 0),
      recent: recent.rows.map((row) => ({ id: String(row.id), status: row.status, total: Number(row.question_count), correct: Number(row.correct_count) })),
    };
  }

  async history(student: PracticeStudent, page: number): Promise<{ rows: Array<{ id: string; date: string; category: string; correct: boolean }>; more: boolean }> {
    const result = await this.client.queryObject<{ id: string | bigint; answered_at: Date | string; category_name: string; correct: boolean }>`
      SELECT d.id, d.answered_at, v.category_name, o.correct
      FROM practice_deliveries d JOIN question_versions v ON v.id = d.version_id
      JOIN question_options o ON o.version_id = d.version_id AND o.position = d.selected_option
      WHERE d.user_id = ${student.id} AND v.exam_type_id = ${student.examId}
      ORDER BY d.id DESC LIMIT 6 OFFSET ${page * 5}
    `;
    return { rows: result.rows.slice(0, 5).map((row) => ({ id: String(row.id), date: new Date(row.answered_at).toISOString(), category: row.category_name, correct: row.correct })), more: result.rows.length > 5 };
  }

  async insights(student: PracticeStudent, page: number): Promise<{ rows: Array<{ id: string; name: string; answered: number; correct: number }>; more: boolean }> {
    const result = await this.client.queryObject<{ category_id: string | bigint; name: string; answered: number | bigint; correct: number | bigint }>`
      SELECT v.category_id, MAX(v.category_name) AS name, COUNT(*) AS answered,
        SUM(CASE WHEN o.correct THEN 1 ELSE 0 END) AS correct
      FROM practice_usage u JOIN practice_deliveries d ON d.id = u.first_delivery_id
      JOIN question_versions v ON v.id = d.version_id
      JOIN question_options o ON o.version_id = d.version_id AND o.position = d.selected_option
      WHERE u.user_id = ${student.id} AND v.exam_type_id = ${student.examId}
      GROUP BY v.category_id ORDER BY v.category_id LIMIT 6 OFFSET ${page * 5}
    `;
    return { rows: result.rows.slice(0, 5).map((row) => ({ id: String(row.category_id), name: row.name, answered: Number(row.answered), correct: Number(row.correct) })), more: result.rows.length > 5 };
  }

  async recommendation(student: PracticeStudent): Promise<string | null> {
    const rows = await this.client.queryObject<{ category_id: string | bigint }>`
      SELECT v.category_id
      FROM practice_usage u JOIN practice_deliveries d ON d.id = u.first_delivery_id
      JOIN question_versions v ON v.id = d.version_id
      JOIN question_options o ON o.version_id = d.version_id AND o.position = d.selected_option
      WHERE u.user_id = ${student.id} AND v.exam_type_id = ${student.examId} AND EXISTS (
        SELECT 1 FROM questions q JOIN question_versions cv ON cv.id = q.current_version_id
        JOIN categories c ON c.id = cv.category_id JOIN exam_types e ON e.id = cv.exam_type_id
        WHERE q.status = 'PUBLISHED' AND c.active = TRUE AND e.active = TRUE AND c.exam_type_id = e.id
          AND cv.category_id = v.category_id AND cv.exam_type_id = ${student.examId}
          AND (cv.free_pool = TRUE OR (${student.accessLevel === "LIFETIME"} = TRUE AND cv.premium_pool = TRUE))
          AND NOT EXISTS (SELECT 1 FROM practice_usage used WHERE used.user_id = ${student.id} AND used.exam_type_id = ${student.examId} AND used.question_id = q.id)
      )
      GROUP BY v.category_id
      HAVING COUNT(*) >= 5 AND SUM(CASE WHEN o.correct THEN 1 ELSE 0 END) * 100.0 / COUNT(*) < 60
      ORDER BY COALESCE((SELECT MAX(recent.id) FROM practice_deliveries recent
        JOIN question_versions rv ON rv.id = recent.version_id
        WHERE recent.user_id = ${student.id} AND recent.exam_type_id = ${student.examId} AND rv.category_id = v.category_id), 0), v.category_id LIMIT 1
    `;
    return rows.rows[0] ? String(rows.rows[0].category_id) : null;
  }

  async maintenanceLanguage(telegramId: string): Promise<"en" | "am" | null> {
    const rows = await this.client.queryObject<{ language: string }>`
      SELECT COALESCE(u.preferred_language, 'en') AS language FROM app_settings s
      LEFT JOIN bot_users u ON u.telegram_user_id = ${telegramId}
      WHERE s.id = 1 AND s.maintenance_enabled = TRUE
    `;
    if (!rows.rows[0]) return null;
    return rows.rows[0].language === "am" ? "am" : "en";
  }
}

export class PostgresPracticeStore {
  constructor(private readonly database: PostgresDatabase) {}

  async withAction<T>(operation: (unit: PracticeUnitOfWork) => Promise<T>): Promise<T> {
    return await this.database.transaction(async (client) => await operation(new PracticeUnitOfWork(client)));
  }
}
