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
 * The `mcp-host` config schema exactly as the host sends it: `hostConfigSchema` in
 * computers/mcphost/.../ConfigSchema.kt, serialized. Keep it in step with that file.
 */
val MCP_HOST_SCHEMA_JSON = """
{
    "type": "object",
    "properties": {
        "servers": {
            "type": "array",
            "title": "MCP servers",
            "description": "Servers that give Clarity tools for your other accounts. Each runs on your server, alone, and reaches only the hosts you list.",
            "minItems": 0,
            "x-item-title": "label",
            "x-item-subtitle": "package",
            "x-presets": [
                {
                    "title": "GitHub",
                    "description": "GitHub's own MCP server (release v2.0.3, Linux x86_64). Create a fine-grained token at github.com/settings/personal-access-tokens with read-only access to Contents, Issues, Pull requests and Metadata for the repositories you choose; give write access only if you want Clarity to change things (each change still waits for your OK). Notifications need a classic token with the notifications scope.",
                    "values": {
                        "label": "GitHub",
                        "description": "Your GitHub repos, issues and pull requests",
                        "kind": "github-release",
                        "package": "github/github-mcp-server",
                        "version": "v2.0.3",
                        "asset": "github-mcp-server_Linux_x86_64.tar.gz",
                        "sha256": "f797c20fe02a755865d0e2d46184ab47e31bf1619b0855b8d89c531e45a651c2",
                        "command": "github-mcp-server",
                        "args": [
                            "stdio"
                        ],
                        "env": [
                            "GITHUB_TOOLSETS=context,repos,issues,pull_requests,notifications"
                        ],
                        "secretEnv": "GITHUB_PERSONAL_ACCESS_TOKEN",
                        "allowHosts": [
                            "api.github.com",
                            "raw.githubusercontent.com"
                        ]
                    }
                },
                {
                    "title": "Canvas",
                    "description": "Canvas LMS (canvas-mcp 1.16.0). In Canvas, open Account → Settings → New Access Token (some schools issue tokens on request). Canvas tokens carry your whole account's access; Clarity asks before anything that changes. Set CANVAS_API_URL to your school's address ending in /api/v1, and add that host to the hosts it may reach.",
                    "values": {
                        "label": "Canvas",
                        "description": "Your courses, assignments and due dates in Canvas",
                        "kind": "pypi",
                        "package": "canvas-mcp",
                        "version": "1.16.0",
                        "command": "canvas-mcp-server",
                        "env": [
                            "CANVAS_API_URL=https://canvas.example.edu/api/v1"
                        ],
                        "secretEnv": "CANVAS_API_TOKEN",
                        "allowHosts": [
                            "canvas.example.edu"
                        ]
                    }
                }
            ],
            "items": {
                "type": "object",
                "required": [
                    "label",
                    "kind"
                ],
                "properties": {
                    "id": {
                        "type": "string",
                        "readOnly": true
                    },
                    "label": {
                        "type": "string",
                        "title": "Name",
                        "description": "What you call it. Clarity's tools for it start with this name.",
                        "examples": [
                            "GitHub"
                        ]
                    },
                    "description": {
                        "type": "string",
                        "title": "What it is for",
                        "description": "One line Clarity reads to know when to use it.",
                        "examples": [
                            "Your GitHub repos, issues and pull requests"
                        ]
                    },
                    "enabled": {
                        "type": "boolean",
                        "title": "On",
                        "default": true
                    },
                    "kind": {
                        "type": "string",
                        "title": "Where it comes from",
                        "enum": [
                            "npm",
                            "pypi",
                            "github-release",
                            "remote"
                        ],
                        "x-enum-titles": [
                            "npm package",
                            "Python package",
                            "GitHub release binary",
                            "Remote URL"
                        ],
                        "default": "npm"
                    },
                    "package": {
                        "type": "string",
                        "title": "Package",
                        "description": "npm or Python package name, or owner/repo for a GitHub release.",
                        "examples": [
                            "@scope/some-mcp-server"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "version": {
                        "type": "string",
                        "title": "Version",
                        "description": "An exact version or release tag. Ranges and \"latest\" are not accepted, so nothing changes on its own.",
                        "examples": [
                            "1.2.3"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "asset": {
                        "type": "string",
                        "title": "Release file",
                        "description": "GitHub release only: the file to download.",
                        "examples": [
                            "server_Linux_x86_64.tar.gz"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "github-release"
                            ]
                        }
                    },
                    "sha256": {
                        "type": "string",
                        "title": "SHA-256 of the release file",
                        "description": "GitHub release only: the file is checked against it before it is kept.",
                        "x-visible-when": {
                            "kind": [
                                "github-release"
                            ]
                        }
                    },
                    "command": {
                        "type": "string",
                        "title": "Command",
                        "description": "The command to run, when the package has more than one (for a release: the file inside the archive).",
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "args": {
                        "type": "array",
                        "title": "Arguments",
                        "description": "Passed to the command, one per entry.",
                        "items": {
                            "type": "string"
                        },
                        "examples": [
                            "stdio"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "env": {
                        "type": "array",
                        "title": "Settings",
                        "description": "KEY=value entries for the server's environment. Not for secrets.",
                        "items": {
                            "type": "string"
                        },
                        "examples": [
                            "LOG_LEVEL=info"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "secretEnv": {
                        "type": "string",
                        "title": "Token variable",
                        "description": "The environment variable that receives the token.",
                        "examples": [
                            "API_TOKEN"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "secret": {
                        "type": "string",
                        "title": "Token",
                        "description": "Kept sealed on your server; only this server sees it. For a remote URL it is sent as a bearer token.",
                        "format": "password",
                        "writeOnly": true,
                        "x-secret": true
                    },
                    "secretEnv2": {
                        "type": "string",
                        "title": "Second secret variable",
                        "description": "Only if the server needs a second secret.",
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "secret2": {
                        "type": "string",
                        "title": "Second secret",
                        "description": "Kept sealed like the token.",
                        "format": "password",
                        "writeOnly": true,
                        "x-secret": true,
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "url": {
                        "type": "string",
                        "format": "uri",
                        "title": "Server URL",
                        "description": "Remote URL only: the server's streamable HTTP address (https).",
                        "examples": [
                            "https://mcp.example.com/mcp"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "remote"
                            ]
                        }
                    },
                    "allowHosts": {
                        "type": "array",
                        "title": "Hosts it may reach",
                        "description": "The only internet hosts this server can connect to. A leading dot allows subdomains.",
                        "items": {
                            "type": "string"
                        },
                        "examples": [
                            "api.example.com"
                        ]
                    }
                }
            }
        }
    }
}
"""

val MCP_HOST_SCHEMA: JsonObject get() = Json.parseToJsonElement(MCP_HOST_SCHEMA_JSON).jsonObject
