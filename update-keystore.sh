#!/bin/bash
set -e

# Usage function
function usage() {
  echo "Usage: $0 <keystore-file> <action> <key> [value]"
  echo ""
  echo "Actions:"
  echo "  import <key> <value>  - Import a new key-value pair"
  echo "  replace <key> <value> - Replace an existing key-value pair"
  echo "  delete <key>          - Delete a key"
  echo ""
  echo "Examples:"
  echo "  $0 rules.jks import quarkus.mailer.host smtp.example.com"
  echo "  $0 rules.jks replace quarkus.mailer.port 587"
  echo "  $0 rules.jks delete quarkus.mailer.host"
  exit 1
}

# Check minimum arguments
if [ $# -lt 3 ]; then
  usage
fi

FILE="$1"
ACTION="$2"
KEY="$3"
VALUE="$4"

# Validate file exists for delete/replace actions
if [ "$ACTION" != "import" ] && [ ! -f "$FILE" ]; then
  echo "Error: Keystore file '$FILE' does not exist"
  exit 1
fi

# Prompt for password securely
if [ -z "$PASS" ]; then
  read -s -p "Enter keystore password: " PASS
  echo ""
fi

# Prompt for value if not provided for import/replace
if [ "$ACTION" = "import" ] || [ "$ACTION" = "replace" ]; then
  if [ -z "$VALUE" ]; then
    read -s -p "Enter value for key '$KEY': " VALUE
    echo ""
    if [ -z "$VALUE" ]; then
      echo "Error: Value cannot be empty for $ACTION action"
      exit 1
    fi
  fi
fi

function importKey() {
  echo "$VALUE" | keytool -importpass -v \
    -keystore "${FILE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -alias "$KEY"
}

function replaceKey() {
  # Delete existing entry
  keytool -delete \
    -keystore "${FILE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -alias "$KEY" 2>/dev/null || echo "Note: Key '$KEY' did not exist"

  # Import new value
  echo "$VALUE" | keytool -importpass -v \
    -keystore "${FILE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -alias "$KEY"
}

function deleteKey() {
  keytool -delete \
    -keystore "${FILE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -alias "$KEY"
}

# Execute action
case "$ACTION" in
  import)
    importKey
    ;;
  replace)
    replaceKey
    ;;
  delete)
    deleteKey
    ;;
  *)
    echo "Error: Unknown action '$ACTION'"
    usage
    ;;
esac

echo "Operation completed successfully"






