# TRIMAX SMTP email setup

Employee account creation now requires SMTP delivery so the new employee can receive a one-time password setup code. Email credentials must be configured outside the repository.

## Required environment variables

Set these variables in the environment used to launch the TRIMAX desktop application:

- `TRIMAX_SMTP_HOST` — SMTP server hostname from your email provider
- `TRIMAX_SMTP_PORT` — usually `587` for STARTTLS
- `TRIMAX_SMTP_USER` — SMTP account / sender login
- `TRIMAX_SMTP_PASSWORD` — SMTP password or provider-generated app password
- `TRIMAX_SMTP_FROM` — verified sender address
- `TRIMAX_SMTP_STARTTLS` — `true` for STARTTLS (default)

Example for the current PowerShell window (replace every example value with values from your email provider):

```powershell
$env:TRIMAX_SMTP_HOST = "smtp.example.com"
$env:TRIMAX_SMTP_PORT = "587"
$env:TRIMAX_SMTP_USER = "trimax@example.com"
$env:TRIMAX_SMTP_PASSWORD = "<provider-generated-app-password>"
$env:TRIMAX_SMTP_FROM = "trimax@example.com"
$env:TRIMAX_SMTP_STARTTLS = "true"
mvn clean test
```

Launch the desktop app from that same PowerShell window so it inherits the variables. Restart the app after changing configuration.

## Account creation behavior

1. An authenticated Admin opens **Agents & Drivers → Create Employee Account**.
2. The Admin enters the employee's name, email, contact number, and Agent/Driver role. The Admin no longer chooses or emails a password.
3. TRIMAX creates a random initial password internally and stores only its password hash.
4. TRIMAX sends a one-time setup code to the employee. The code expires after 15 minutes and is stored hashed in the existing `password_resets` table.
5. If SMTP is not configured or email delivery fails, the database transaction is rolled back so an account is not silently created without its setup email.

Never commit SMTP passwords, provider API keys, or service-account credentials to Git.
