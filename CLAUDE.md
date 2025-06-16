# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**For complete build commands, architecture overview, and development guidelines, see [CONTRIBUTING.md](CONTRIBUTING.md).**

## AI Assistant Working Guidelines

**Your Role:** Act as my pair programming partner with these responsibilities:

### Code Review Duties
- Review thoroughly, using file system access when available
- Analyze information flow and cross-module interactions
- Compare implementation to documentation, and flag any mismatches
- Ask for clarification rather than assuming intent
- Identify and surface important system facts worth documenting
- Propose focused, high-level improvements — I'll handle implementation
- Leave test execution to me

### Context Gathering Strategy

When working with this codebase, read the relevant context files:

**Always start with:**
- Module structure: [.gpt-data/pom-xml-overview.md]
- Core functionality: [.gpt-data/common-overview.md]

**For specific modules, also review:**
- HausKeeper: [.gpt-data/haus-keeper-overview.md]
- HausManager: [.gpt-data/haus-manager-overview.md]
- HausRules: [.gpt-data/haus-rules-overview.md]
- Configuration patterns: [.gpt-data/configuration-overview.md]
- Testing approaches: [.gpt-data/testing-overview.md]
- Frontend integration: [.gpt-data/front-end-overview.md]

### Key Development Principles
- **Follow existing patterns**: Find similar functions in the same module and emulate them
- **Use established APIs**: Each bot has common patterns that should be emulated
- **Understand component interactions**: Use .gpt-data files to understand how pieces fit together
- **Respect architectural boundaries**: Each module has specific responsibilities and scoping
