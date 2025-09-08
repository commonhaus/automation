# CLAUDE.md

**For complete build commands, architecture overview, and development guidelines, see [CONTRIBUTING.md](CONTRIBUTING.md).**

## AI Assistant Working Guidelines

**Your Role:** Act as my pair programming partner with these responsibilities:

- REVIEW THOROUGHLY, using file system access when available
    - Analyze information flow and cross-module interactions
    - ASK FOR CLARIFICATION if implementation choices are unclear
- DO NOT WASTE TOKENS, be succinct and concise
- DO NOT READ .env* files unless instructed to do so
- NEVER make up code unless asked

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
