import { Pool } from "jsr:@db/postgres@0.19.5";

export type QueryClient = {
  queryObject<T>(strings: TemplateStringsArray, ...values: unknown[]): Promise<{ rows: T[] }>;
  queryArray(strings: TemplateStringsArray, ...values: unknown[]): Promise<unknown>;
  release(): void;
};

export type SettingsRow = {
  free_practice_limit: number;
  free_mock_limit: number;
  questions_per_mock: number;
  phone_key_fingerprint: string | null;
};

export class PostgresDatabase {
  private pool: Pool | null = null;

  constructor(private readonly databaseUrl: () => string) {}

  private getPool(): Pool {
    const url = this.databaseUrl();
    if (!url) throw new Error("DATABASE_NOT_CONFIGURED");
    if (!this.pool) this.pool = new Pool(url, 1, true);
    return this.pool;
  }

  async transaction<T>(operation: (client: QueryClient, settings: SettingsRow) => Promise<T>): Promise<T> {
    const client = await this.getPool().connect() as unknown as QueryClient;
    try {
      await client.queryArray`BEGIN`;
      // Matches StudentAccess.lock(): this row serializes student actions with admin changes.
      const result = await client.queryObject<SettingsRow>`
        SELECT free_practice_limit, free_mock_limit, questions_per_mock, phone_key_fingerprint
        FROM app_settings WHERE id = 1 FOR UPDATE
      `;
      const settings = result.rows[0];
      if (!settings) throw new Error("APP_SETTINGS_MISSING");
      const value = await operation(client, settings);
      await client.queryArray`COMMIT`;
      return value;
    } catch (error) {
      try { await client.queryArray`ROLLBACK`; } catch { /* preserve original failure category */ }
      throw error;
    } finally {
      client.release();
    }
  }

  async withConnection<T>(operation: (client: QueryClient) => Promise<T>): Promise<T> {
    const client = await this.getPool().connect() as unknown as QueryClient;
    try { return await operation(client); }
    finally { client.release(); }
  }

  async healthCheck(): Promise<void> {
    await this.withConnection(async (client) => { await client.queryArray`SELECT 1`; });
  }

  async close(): Promise<void> {
    if (this.pool) await this.pool.end();
    this.pool = null;
  }
}
