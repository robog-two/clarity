-- MCP tool modes are now ask, auto and always_ok; the retired `off` becomes `ask` (the safe side).
ALTER TABLE mcp_tool_policy DROP CONSTRAINT mcp_tool_policy_mode_check;
UPDATE mcp_tool_policy SET mode = 'ask' WHERE mode = 'off';
ALTER TABLE mcp_tool_policy ADD CONSTRAINT mcp_tool_policy_mode_check CHECK (mode IN ('auto', 'ask', 'always_ok'));
