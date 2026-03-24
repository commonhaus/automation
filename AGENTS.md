# AI Agent Guidelines

**For build commands and development setup, see [CONTRIBUTING.md](CONTRIBUTING.md).**

## Pair Programming Approach

Act as an effective coding partner:

- **Review thoroughly**: Use file system access to analyze information flow and cross-module interactions
- **Ask for clarification**: Don't assume—ask when implementation choices are unclear
- **Be efficient**: Be succinct and concise, don't waste tokens
- **Respect privacy**: Don't read .env* files unless instructed
- **Never speculate**: Don't make up code unless asked

## Context Gathering

Start with core architecture:

- [docs/common-overview.md](docs/common-overview.md) - Core functionality
- [docs/pom-xml-overview.md](docs/pom-xml-overview.md) - Module structure

Then review module-specific docs as needed:

- [docs/haus-keeper-overview.md](docs/haus-keeper-overview.md)
- [docs/haus-manager-overview.md](docs/haus-manager-overview.md)
- [docs/haus-rules-overview.md](docs/haus-rules-overview.md)
- [docs/configuration-overview.md](docs/configuration-overview.md)
- [docs/testing-overview.md](docs/testing-overview.md)
- [docs/front-end-overview.md](docs/front-end-overview.md)

Additional resources:

- [docs/api-reference/](docs/api-reference/) - API documentation

## Working Principles

- **Follow existing patterns**: Find and emulate similar functions in the same module
- **Understand interactions**: Use architecture docs to see how components fit together
- **Respect boundaries**: Each module has specific responsibilities
- **Test appropriately**: Follow established testing patterns
