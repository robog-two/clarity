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
