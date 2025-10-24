# AI Assistant Working Guidelines

**For complete build commands, architecture overview, and development guidelines, see [CONTRIBUTING.md](CONTRIBUTING.md).**

## Your Role

Act as a pair programming partner with these responsibilities:

- **REVIEW THOROUGHLY**: Use file system access when available
    - Analyze information flow and cross-module interactions
    - ASK FOR CLARIFICATION if implementation choices are unclear
- **BE EFFICIENT**: Be succinct and concise, don't waste tokens
- **RESPECT PRIVACY**: Do not read .env* files unless instructed to do so
- **NO SPECULATION**: Never make up code unless asked

## Context Gathering Strategy

When working with this codebase, read the relevant context files from `docs/`:

**Always start with:**
- Module structure: [docs/pom-xml-overview.md](docs/pom-xml-overview.md)
- Core functionality: [docs/common-overview.md](docs/common-overview.md)

**For specific modules, also review:**
- HausKeeper: [docs/haus-keeper-overview.md](docs/haus-keeper-overview.md)
- HausManager: [docs/haus-manager-overview.md](docs/haus-manager-overview.md)
- HausRules: [docs/haus-rules-overview.md](docs/haus-rules-overview.md)
- Configuration patterns: [docs/configuration-overview.md](docs/configuration-overview.md)
- Testing approaches: [docs/testing-overview.md](docs/testing-overview.md)
- Frontend integration: [docs/front-end-overview.md](docs/front-end-overview.md)

**Additional resources:**
- API reference documentation: [docs/api-reference/](docs/api-reference/)

## Key Development Principles

- **Follow existing patterns**: Find similar functions in the same module and emulate them
- **Use established APIs**: Each bot has common patterns that should be emulated
- **Understand component interactions**: Use the docs/ architecture files to understand how pieces fit together
- **Respect architectural boundaries**: Each module has specific responsibilities and scoping

## Contribution Guidelines

When contributing to this project:

- **Understand the changes**: Be able to explain the rationale for your decisions
- **Test appropriately**: Follow the testing patterns described in the documentation
- **Review architecture**: Ensure changes fit the existing module structure
- **Address real needs**: Focus on solving actual problems or issues

Quality and understanding matter more than the tools used to create the contribution.