package net.robog.clarity.app.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The mail and dav config schemas as docs/workstreams/connector-accounts.md describes them. */
val MAIL_ACCOUNTS_SCHEMA_JSON = """
{
  "type": "object",
  "description": "I read your inboxes and save drafts.",
  "required": ["accounts"],
  "properties": {
    "accounts": {
      "type": "array",
      "title": "Accounts",
      "minItems": 1,
      "x-item-title": "label",
      "x-item-subtitle": "username",
      "x-presets": [
        {"title": "iCloud", "description": "Use an app-specific password from account.apple.com",
         "values": {"imapHost": "imap.mail.me.com", "imapPort": 993, "imapSecurity": "ssl",
                    "smtpHost": "smtp.mail.me.com", "smtpPort": 587, "smtpSecurity": "starttls"}},
        {"title": "Fastmail", "description": "Use an app password",
         "values": {"imapHost": "imap.fastmail.com", "imapPort": 993, "imapSecurity": "ssl",
                    "smtpHost": "smtp.fastmail.com", "smtpPort": 465, "smtpSecurity": "ssl"}},
        {"title": "Gmail", "description": "Needs 2-step verification and an app password",
         "values": {"imapHost": "imap.gmail.com", "imapPort": 993, "imapSecurity": "ssl",
                    "smtpHost": "smtp.gmail.com", "smtpPort": 465, "smtpSecurity": "ssl"}}
      ],
      "items": {
        "type": "object",
        "required": ["label", "imapHost", "imapPort", "imapSecurity", "smtpHost", "smtpPort", "smtpSecurity", "username", "password"],
        "properties": {
          "id": {"type": "string", "readOnly": true},
          "label": {"type": "string", "title": "Name", "description": "Work, Personal...", "examples": ["Work"]},
          "imapHost": {"type": "string", "title": "IMAP host", "examples": ["imap.example.com"]},
          "imapPort": {"type": "integer", "title": "IMAP port", "minimum": 1, "maximum": 65535, "default": 993},
          "imapSecurity": {"type": "string", "title": "IMAP security", "enum": ["ssl", "starttls", "none"],
                           "x-enum-titles": ["SSL/TLS", "STARTTLS", "None"], "default": "ssl"},
          "smtpHost": {"type": "string", "title": "SMTP host"},
          "smtpPort": {"type": "integer", "title": "SMTP port", "minimum": 1, "maximum": 65535, "default": 465},
          "smtpSecurity": {"type": "string", "title": "SMTP security", "enum": ["ssl", "starttls", "none"],
                           "x-enum-titles": ["SSL/TLS", "STARTTLS", "None"], "default": "ssl"},
          "username": {"type": "string", "title": "Username"},
          "password": {"type": "string", "title": "Password", "format": "password", "writeOnly": true},
          "fromAddress": {"type": "string", "title": "From address", "format": "email"},
          "fromName": {"type": "string", "title": "From name"},
          "folders": {"type": "array", "title": "Folders", "items": {"type": "string"}, "default": ["INBOX"]},
          "history": {"type": "string", "title": "Mail history", "enum": ["30d", "90d", "1y", "all"],
                      "x-enum-titles": ["30 days", "90 days", "1 year", "All time"], "default": "30d"}
        }
      }
    }
  }
}
"""

val DAV_ACCOUNTS_SCHEMA_JSON = """
{
  "type": "object",
  "required": ["accounts"],
  "properties": {
    "accounts": {
      "type": "array",
      "title": "Accounts",
      "minItems": 1,
      "x-item-title": "label",
      "x-item-subtitle": "username",
      "x-presets": [
        {"title": "iCloud", "description": "Use an app-specific password from account.apple.com",
         "values": {"caldavUrl": "https://caldav.icloud.com", "carddavUrl": "https://contacts.icloud.com"}},
        {"title": "Fastmail",
         "values": {"caldavUrl": "https://caldav.fastmail.com/dav/", "carddavUrl": "https://carddav.fastmail.com/dav/"}},
        {"title": "Nextcloud", "values": {"baseUrl": "https://cloud.example.com/remote.php/dav"}}
      ],
      "items": {
        "type": "object",
        "required": ["label", "username", "password"],
        "properties": {
          "id": {"type": "string", "readOnly": true},
          "label": {"type": "string", "title": "Name"},
          "baseUrl": {"type": "string", "title": "Server address", "format": "uri"},
          "caldavUrl": {"type": "string", "title": "Calendar address", "format": "uri"},
          "carddavUrl": {"type": "string", "title": "Contacts address", "format": "uri"},
          "webdavUrl": {"type": "string", "title": "Files address", "format": "uri"},
          "username": {"type": "string", "title": "Username"},
          "password": {"type": "string", "title": "Password", "format": "password"},
          "email": {"type": "string", "title": "Email", "format": "email"},
          "calendars": {"type": "array", "title": "Calendars", "items": {"type": "string"}},
          "addressBooks": {"type": "array", "title": "Address books", "items": {"type": "string"}},
          "webdavFolders": {"type": "array", "title": "Folders", "items": {"type": "string"}}
        }
      }
    }
  }
}
"""

val MAIL_ACCOUNTS_SCHEMA: JsonObject get() = Json.parseToJsonElement(MAIL_ACCOUNTS_SCHEMA_JSON).jsonObject
val DAV_ACCOUNTS_SCHEMA: JsonObject get() = Json.parseToJsonElement(DAV_ACCOUNTS_SCHEMA_JSON).jsonObject

/**
 * The `mcp-host` config schema as contract 1 of docs/workstreams/mcp.md describes it. The preset
 * values are made up for the tests (the host lane verifies the real ones).
 */
val MCP_HOST_SCHEMA_JSON = """
{
  "type": "object",
  "description": "MCP servers run on your server, each with its own token.",
  "properties": {
    "servers": {
      "type": "array",
      "title": "Servers",
      "minItems": 0,
      "x-item-title": "label",
      "x-item-subtitle": "package",
      "x-presets": [
        {"title": "GitHub",
         "description": "Create a fine-grained token at github.com/settings/tokens with read access to issues and pull requests.",
         "values": {"label": "GitHub", "description": "Your GitHub repos, issues and PRs", "kind": "github-release",
                    "package": "github/github-mcp-server", "version": "v1.0.0", "asset": "github-mcp-server_Linux_x86_64.tar.gz",
                    "sha256": "0000000000000000000000000000000000000000000000000000000000000000", "command": "github-mcp-server",
                    "args": ["stdio"], "env": ["GITHUB_TOOLSETS=repos,issues,pull_requests,notifications"],
                    "secretEnv": "GITHUB_PERSONAL_ACCESS_TOKEN", "allowHosts": ["api.github.com"]}},
        {"title": "Canvas",
         "description": "Create an access token under Account → Settings in Canvas.",
         "values": {"label": "Canvas", "description": "Your courses, assignments and grades", "kind": "npm",
                    "package": "canvas-mcp-example", "version": "1.2.3", "secretEnv": "CANVAS_API_TOKEN",
                    "env": ["CANVAS_BASE_URL=https://canvas.example.edu"], "allowHosts": ["canvas.example.edu"]}}
      ],
      "items": {
        "type": "object",
        "required": ["label"],
        "properties": {
          "id": {"type": "string", "readOnly": true},
          "label": {"type": "string", "title": "Name", "description": "Also the name of its tools and notes", "examples": ["GitHub"]},
          "description": {"type": "string", "title": "Description", "examples": ["Your GitHub repos, issues and PRs"]},
          "enabled": {"type": "boolean", "title": "Enabled", "default": true},
          "kind": {"type": "string", "title": "Kind", "enum": ["npm", "pypi", "github-release", "remote"],
                   "x-enum-titles": ["npm package", "Python package", "GitHub release binary", "Remote URL"]},
          "package": {"type": "string", "title": "Package", "examples": ["@scope/server"]},
          "version": {"type": "string", "title": "Version", "description": "An exact version or tag"},
          "asset": {"type": "string", "title": "Release asset"},
          "sha256": {"type": "string", "title": "SHA-256"},
          "command": {"type": "string", "title": "Command"},
          "args": {"type": "array", "title": "Arguments", "items": {"type": "string"}},
          "env": {"type": "array", "title": "Environment", "items": {"type": "string"}, "examples": ["KEY=value"]},
          "secretEnv": {"type": "string", "title": "Token variable", "examples": ["GITHUB_PERSONAL_ACCESS_TOKEN"]},
          "secret": {"type": "string", "title": "Token", "x-secret": true},
          "secretEnv2": {"type": "string", "title": "Second secret variable"},
          "secret2": {"type": "string", "title": "Second secret", "x-secret": true},
          "url": {"type": "string", "title": "URL", "format": "uri"},
          "allowHosts": {"type": "array", "title": "Allowed hosts", "items": {"type": "string"}, "examples": ["api.github.com"]}
        }
      }
    }
  }
}
"""

val MCP_HOST_SCHEMA: JsonObject get() = Json.parseToJsonElement(MCP_HOST_SCHEMA_JSON).jsonObject
