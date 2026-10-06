export class PracticeError extends Error {
  constructor(key) {
    super(key);
    this.name = "PracticeError";
    this.key = key;
  }
}

function percent(correct, answered) {
  return answered === 0 ? 0 : Math.round(correct * 10000 / answered) / 100;
}

function view(student, delivery, question) {
  if (!delivery || !question || question.examId !== student.examId) throw new PracticeError("student.invalid");
  const selected = delivery.selectedOption;
  const choice = selected === null ? null : question.options.find((option) => option.position === selected);
  if (selected !== null && !choice) throw new PracticeError("student.invalid");
  return { student, delivery, question, answered: selected !== null, correct: choice?.correct === true };
}

export class PracticeService {
  constructor(store) {
    this.store = store;
  }

  async withStudent(telegramId, operation) {
    try {
      return await this.store.withAction(async (unit) => {
        const student = await unit.student(telegramId);
        if (!student) throw new PracticeError("student.register");
        return await operation(unit, student);
      });
    } catch (error) {
      if (error instanceof Error && error.message === "STUDENT_INVALID") throw new PracticeError("student.invalid");
      throw error;
    }
  }

  async profile(telegramId) {
    return await this.withStudent(telegramId, async (_unit, student) => student);
  }

  async categories(telegramId, page = 0) {
    if (!Number.isSafeInteger(page) || page < 0 || page > 1_000_000) throw new PracticeError("student.invalid");
    return await this.withStudent(telegramId, async (unit, student) => ({
      student,
      categories: await unit.categories(student, page),
    }));
  }

  async resume(telegramId) {
    return await this.withStudent(telegramId, async (unit, student) => {
      const delivery = await unit.currentDelivery(student);
      if (!delivery) return null;
      return view(student, delivery, await unit.frozen(delivery.versionId));
    });
  }

  /** @param {string} telegramId @param {string} updateId @param {{categoryId?: string | null, previousId?: string | null, review?: boolean}} [options] */
  async next(telegramId, updateId, { categoryId = null, previousId = null, review = false } = {}) {
    if (!/^\d{1,20}$/.test(String(updateId)) || BigInt(updateId) > 9223372036854775807n) throw new PracticeError("student.invalid");
    return await this.withStudent(telegramId, async (unit, student) => {
      const replay = await unit.deliveryByUpdateId(String(updateId));
      if (replay) {
        if (replay.userId !== student.id) throw new PracticeError("student.invalid");
        return view(student, replay, await unit.frozen(replay.versionId));
      }
      const respond = async (delivery) => {
        const result = view(student, delivery, await unit.frozen(delivery.versionId));
        await unit.recordDeliveryUpdate(student, String(updateId), delivery);
        return result;
      };

      let old = previousId === null ? null : await unit.ownedDelivery(student, previousId);
      if (previousId !== null && !old) throw new PracticeError("student.invalid");
      if (old) {
        categoryId = old.categoryFilter;
        const linkedId = review ? old.reviewDeliveryId : old.nextDeliveryId;
        if (linkedId !== null) {
          const linked = await unit.ownedDelivery(student, linkedId);
          if (!linked) throw new PracticeError("student.invalid");
          return await respond(linked);
        }
      }

      await unit.validateCategory(student, categoryId);
      if (previousId === null) {
        const active = await unit.currentDelivery(student);
        if (active && active.selectedOption === null && active.categoryFilter === categoryId
          && (!review || await unit.used(student, active.questionId))) {
          return await respond(active);
        }
      }
      if (!review && student.accessLevel !== "LIFETIME" && student.practiceLimit - student.practiceUsed <= 0) {
        throw new PracticeError("practice.limit");
      }
      let versionId = await unit.selectVersion(student, categoryId, review);
      if (versionId === null && student.accessLevel === "LIFETIME" && !review) {
        versionId = await unit.selectVersion(student, categoryId, true);
      }
      if (versionId === null) throw new PracticeError("practice.empty");

      const delivery = await unit.createDelivery(student, versionId, categoryId);
      if (old) await unit.link(old, delivery.id, review);
      await unit.setCurrentDelivery(student, delivery.id);
      return await respond(delivery);
    });
  }

  async answer(telegramId, deliveryId, optionPosition) {
    return await this.withStudent(telegramId, async (unit, student) => {
      const delivery = await unit.ownedDelivery(student, deliveryId);
      if (!delivery) throw new PracticeError("student.invalid");
      const question = await unit.frozen(delivery.versionId);
      if (!question || question.examId !== student.examId) throw new PracticeError("student.invalid");
      if (!Number.isSafeInteger(optionPosition) || !question.options.some((option) => option.position === optionPosition)) {
        throw new PracticeError("student.invalid");
      }
      if (delivery.selectedOption !== null) return view(student, delivery, question);

      const used = await unit.used(student, question.id);
      if (!used && student.accessLevel !== "LIFETIME" && student.practiceLimit - student.practiceUsed <= 0) {
        throw new PracticeError("practice.limit");
      }
      const result = await unit.recordAnswer(student, delivery, optionPosition);
      student.practiceUsed = result.practiceUsed;
      const saved = await unit.ownedDelivery(student, delivery.id);
      return view(student, saved, question);
    });
  }

  async historyDelivery(telegramId, deliveryId) {
    return await this.withStudent(telegramId, async (unit, student) => {
      const delivery = await unit.ownedDelivery(student, deliveryId);
      if (!delivery || delivery.selectedOption === null) throw new PracticeError("student.invalid");
      return view(student, delivery, await unit.frozen(delivery.versionId));
    });
  }

  async progress(telegramId) {
    return await this.withStudent(telegramId, async (unit, student) => {
      const data = await unit.progress(student);
      return {
        student,
        ...data,
        incorrect: data.answered - data.correct,
        accuracy: percent(data.correct, data.answered),
        categories: data.categories.map((category) => ({
          ...category,
          incorrect: category.answered - category.correct,
          accuracy: percent(category.correct, category.answered),
        })),
      };
    });
  }

  async history(telegramId, page = 0) {
    if (!Number.isSafeInteger(page) || page < 0 || page > 100_000) throw new PracticeError("student.invalid");
    return await this.withStudent(telegramId, async (unit, student) => ({
      student,
      page,
      ...await unit.history(student, page),
    }));
  }

  async insights(telegramId, page = 0) {
    if (!Number.isSafeInteger(page) || page < 0 || page > 100_000) throw new PracticeError("student.invalid");
    return await this.withStudent(telegramId, async (unit, student) => {
      const data = await unit.insights(student, page);
      return {
        student,
        page,
        more: data.more,
        rows: data.rows.map((row) => {
          const accuracy = percent(row.correct, row.answered);
          return {
            ...row,
            accuracy,
            key: row.answered < 5 ? "insights.more" : accuracy < 60 ? "insights.weak" : accuracy >= 80 ? "insights.strong" : "insights.steady",
          };
        }),
      };
    });
  }

  async recommendation(telegramId) {
    return await this.withStudent(telegramId, async (unit, student) => await unit.recommendation(student));
  }

  async maintenanceLanguage(telegramId) {
    return await this.store.withAction(async (unit) => await unit.maintenanceLanguage(telegramId));
  }
}
