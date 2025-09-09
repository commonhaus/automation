FILE=rules.jks
PASS=...

function importKey() {
  echo "$2" | keytool -importpass -v \
    -keystore "${FILE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -alias "$1"
}

function replaceKey() {
  # Delete existing entry
  keytool -delete \
    -keystore "${FILE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -alias "$1"

  # Import new value
  echo "$2" | keytool -importpass -v \
    -keystore "${FILE}" \
    -storetype PKCS12 \
    -storepass "${PASS}" \
    -alias "$1"
}

importKey "quarkus.mailer.host" "smtp.forwardemail.net"
importKey "quarkus.mailer.port" "587"
importKey "quarkus.mailer.start-tls" "REQUIRED"
importKey "quarkus.mailer.login" "required"
importKey "quarkus.mailer.auth-methods" "PLAIN"






