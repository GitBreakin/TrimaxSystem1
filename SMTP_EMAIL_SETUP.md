# TRIMAX email setup (desktop)

The desktop app sends two kinds of email:

- the one-time **password setup code** when an Admin creates an Agent or Driver account
  (Agents & Drivers → Create Employee Account);
- the **password reset code** from "Forgot password" on the sign-in screen.

Until email is set up on a computer, Create Employee Account stops with "Email is not set up on this
computer" and nothing is saved.

## Where the settings go

Put them in the same private `trimax.properties` file that already holds the database settings (in the
project folder, next to the TRIMAX .exe or .jar, or in `%USERPROFILE%\.trimax\trimax.properties`; the first one
found is used). That file is ignored by Git and must never be committed or shared. Restart TRIMAX after changing it.

| Key | Needed | Meaning |
| --- | --- | --- |
| `smtp.host` | yes | SMTP server name, e.g. `smtp.gmail.com` (a name, not an IP address) |
| `smtp.user` | yes | the email account that signs in to the SMTP server |
| `smtp.password` | yes | its password. **Gmail: a 16-letter App Password**, not the normal password |
| `smtp.port` | no | `587` (default, STARTTLS) or `465` (SSL) |
| `smtp.from` | no | sender address; defaults to `smtp.user` |
| `smtp.starttls` | no | `true` (default). Only set `false` for a local test server |

Environment variables `TRIMAX_SMTP_HOST`, `TRIMAX_SMTP_PORT`, `TRIMAX_SMTP_USER`, `TRIMAX_SMTP_PASSWORD`,
`TRIMAX_SMTP_FROM` and `TRIMAX_SMTP_STARTTLS` still work and win over the file.

The connection is always encrypted (STARTTLS on 587, SSL on 465, TLS 1.2 or newer) and the server's
certificate must match `smtp.host`.

## Gmail

1. Use the Gmail account that should send TRIMAX emails. Turn on **2-Step Verification**
   (Google Account → Security).
2. Open **App passwords** (Google Account → Security → 2-Step Verification → App passwords, or
   https://myaccount.google.com/apppasswords), create one named "TRIMAX", and copy the 16 letters.
3. Add to `trimax.properties`:

   ```properties
   smtp.host=smtp.gmail.com
   smtp.user=YOUR_TRIMAX_GMAIL@gmail.com
   smtp.password=THE_16_LETTER_APP_PASSWORD
   ```

   To avoid the password appearing on screen or in the PowerShell history, add it with this (run in the
   folder that has `trimax.properties`):

   ```powershell
   $u = Read-Host "Gmail address"; $s = Read-Host "App password" -AsSecureString; $p = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)) -replace '\s',''; Add-Content -Path .\trimax.properties -Encoding ASCII -Value "`r`nsmtp.host=smtp.gmail.com`r`nsmtp.user=$u`r`nsmtp.password=$p"; Remove-Variable p, s
   ```

4. Restart TRIMAX, open **System Settings → Email**. Status must say "Set up: smtp.gmail.com:587, ...".
5. Press **Send test email...** and send one to yourself. It changes no data. If it fails, the message
   says why (wrong App Password, wrong server name, port blocked, ...).

Google can turn off App Passwords for some accounts (for example, Workspace accounts where the
administrator disabled them). Then use your provider's SMTP relay settings instead.

## What happens when an account is created

1. An Admin opens **Agents & Drivers → Create Employee Account** and enters name, email, contact number
   and role. (Email must be set up on that PC, otherwise nothing is saved.)
2. TRIMAX saves the account, then creates the person's Supabase sign-in for the phone with a
   **temporary password**: the first name and 4 random characters, for example `Kenjie-7Q4M`.
3. The admin sees the temporary password once (with a Copy button), and it is emailed to the person.
   If the email fails, the window says so; give the password to the person yourself.
4. On the phone the person signs in with the email and the temporary password. The app then asks for
   their own password before anything else.
5. If the sign-in step fails (for example no internet), the account is kept and no password is sent;
   open the account and press **Create mobile sign-in**.

The temporary password is never stored by TRIMAX. A forgotten phone password later: open the account →
**Send new setup code**; on the phone: **Set up or reset password**.

Never commit SMTP passwords, provider API keys or service-account credentials to Git.
