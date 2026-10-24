# provision-mobile-accounts (Supabase Edge Function)

Gives desktop-created Agent and Driver accounts a mobile sign-in, without any secret on the desktop or the
phone.

1. The desktop queues a request with "Send mobile invite" (or automatically after "Create employee
   account"). It calls `trimax_private.request_mobile_access(...)`, which checks the role, the Active
   status, the email and duplicates first.
2. This function claims the requests (`trimax_service_claim_provisioning`). For each one it does one of
   three things:
   - creates the Supabase Auth user (unconfirmed, no password, `app_metadata.trimax_provisioned = true`
     for exactly that username) and sends the Supabase invitation email;
   - re-sends the invitation or a password email, if TRIMAX created that user earlier;
   - refuses, if a sign-in for that email exists that TRIMAX did not create.
3. It reports the result back (`trimax_service_finish_provisioning`). The database checks every rule
   again before it links the Auth user to the TRIMAX account.
4. The person opens the email on their phone. The link opens the app (`trimax://auth-callback`), they
   choose a password and are signed in. The role and the Active status always come from the desktop
   account.

The service-role key exists only inside Supabase. The function itself is protected by
`x-trimax-provisioning-secret`, a random value known only to the function and to the database (Vault).

## One-time setup (Supabase dashboard / CLI)

1. **Run the SQL.** Apply `sql/20261024_01_hub_gps_duty_provisioning.sql`, after
   `sql/20261023_01_...` (see `docs/MOBILE_INTEGRATION.md`).
2. **Configure Auth.** In Authentication:
   - URL Configuration → add `trimax://auth-callback` to **Redirect URLs**;
   - Sign In / Providers → Email: keep **Confirm email** on, and turn **Allow new users to sign up**
     off (only invitations create sign-ins);
   - optionally edit the "Invite user" and "Reset password" email templates (keep
     `{{ .ConfirmationURL }}`).
3. **Create a secret.** Generate a random secret of at least 32 characters and keep it in a password
   manager. Never commit it. `openssl rand -hex 32`, or in Windows PowerShell:
   ```powershell
   $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); -join ($b | % { $_.ToString("x2") })
   ```
4. **Deploy the function** (name exactly `provision-mobile-accounts`), either way:
   - **Dashboard, nothing to install:** Edge Functions → Deploy a new function → Via Editor → name it
     `provision-mobile-accounts` → replace the template with a single-file copy of this function
     (`provision.ts` followed by `index.ts` without its `import` line) → Deploy function. Then open the
     function and turn **Verify JWT** off.
   - **CLI:**
     ```
     supabase functions deploy provision-mobile-accounts --no-verify-jwt --project-ref <project-ref>
     ```
     Without Docker, add `--use-api`. Always keep `--no-verify-jwt`: a deploy without it turns JWT
     verification back on and every call fails with "Invalid JWT".

   Then set the function's secrets (Edge Functions → Secrets, or `supabase secrets set ...`):
   `TRIMAX_PROVISIONING_SECRET` = the secret, `TRIMAX_INVITE_REDIRECT_URL` = `trimax://auth-callback`.
   Supabase provides `SUPABASE_URL` and the server key itself (`SUPABASE_SECRET_KEYS` on projects with the
   new API keys, else the legacy `SUPABASE_SERVICE_ROLE_KEY`; the function uses whichever exists). Do not
   put them anywhere else.

   Check it (PowerShell). An empty queue answers `processed: 0`:
   ```powershell
   $secret = Read-Host "Provisioning secret"
   Invoke-RestMethod -Method Post -Uri "https://<project-ref>.supabase.co/functions/v1/provision-mobile-accounts" `
       -Headers @{ "x-trimax-provisioning-secret" = $secret } -ContentType "application/json" -Body "{}"
   ```
   "Invalid JWT" / "Missing authorization header" = Verify JWT is still on. "Unauthorized." = wrong secret.
   "Function is not configured." = `TRIMAX_PROVISIONING_SECRET` missing or shorter than 32 characters.
5. **Run it automatically (optional but recommended):**
   - enable the `pg_net` extension (Database → Extensions);
   - create two Vault secrets (Vault page of the dashboard, or `select vault.create_secret('<value>', '<name>');`):
     `trimax_provisioning_url` = `https://<project-ref>.supabase.co/functions/v1/provision-mobile-accounts`,
     and `trimax_provisioning_secret` = the same secret;
   - run `sql/20261024_02_optional_provisioning_trigger.sql`.

   Without this, requests stay `pending` until you run the check command above, which processes the queue.

## What the desktop shows

The "Mobile invitation" row of the account window shows one of these:
- queued;
- invitation email sent;
- linked;
- failed, with the reason. The usual causes are: a sign-in already exists that TRIMAX did not create,
  the email changed, another account uses the same email, or Supabase refused to send the email (rate
  limit or SMTP).

After fixing the cause, use "Send invite again". The function gives up on a request after 5 attempts.

## Logs

Each run logs one JSON line per request: request id, username and status. Emails, tokens, passwords and
keys are never logged.

## Local test

`supabase/functions/provision-mobile-accounts/provision.ts` is plain TypeScript using only `fetch`.
During development it was run with Node 22 against a local stand-in for Supabase, together with the real
SQL migrations. That run is not part of this repository.
