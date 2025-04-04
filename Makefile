# Makefile for Knowledge Graph Project
# Provides convenient shortcuts for common Maven operations

# Configuration variables
MVN := mvn

# Default target when just running 'make'
.PHONY: all
all: setup-hooks clean format verify test install docs

# Display help information about available targets
.PHONY: help
help:
	@echo "Knowledge Graph Project Make Targets:"
	@echo "  make all       - Setup, format, verify and install the project"
	@echo "  make install   - Install dependencies and build the project"
	@echo "  make build     - Compile and package the project"
	@echo "  make test      - Run all tests"
	@echo "  make verify    - Run all tests and quality checks"
	@echo "  make clean     - Remove all build artifacts"
	@echo "  make docs      - Generate project documentation"
	@echo "  make format    - Format source code"
	@echo "  make help      - Display this help information"

# Install all dependencies and build the project
.PHONY: install
install:
	$(MVN) clean install

# Compile and package the project without running tests
.PHONY: build
build:
	$(MVN) package -DskipTests

# Run tests
.PHONY: test
test:
	$(MVN) test

# Run all tests and quality checks (Checkstyle, PMD, SpotBugs)
.PHONY: verify
verify:
	$(MVN) verify

# Clean the project
.PHONY: clean
clean:
	$(MVN) clean
	rm -rf .pre-commit-cache

# Generate project documentation
.PHONY: docs
docs:
	$(MVN) site

# Format source code using google-java-format
.PHONY: format
format:
	$(MVN) spotless:apply

# Setup pre-commit hooks
.PHONY: setup-hooks
setup-hooks:
	pip install pre-commit
	pre-commit install

# Update dependencies to latest versions
.PHONY: update-dependencies
update-dependencies:
	$(MVN) versions:use-latest-versions

# Continuous integration checks (what would run in CI)
.PHONY: ci
ci: clean verify

# Shorthand for installing dependencies only
.PHONY: deps
deps:
	$(MVN) dependency:resolve

# Check for dependency updates
.PHONY: check-updates
check-updates:
	$(MVN) versions:display-dependency-updates
	$(MVN) versions:display-plugin-updates
