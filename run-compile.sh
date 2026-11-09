#!/usr/bin/env bash
# Сборка самодостаточного uber-JAR: все зависимости (Vert.x, jOOQ, Jackson,
# PostgreSQL driver, JAXB runtime, HikariCP, SLF4J) внутри архива.
# В манифесте НЕТ Class-Path.
set -euo pipefail
cd "$(dirname "$0")"

export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"

PROJECT_NAME="payment-system"

# 1. Генерация jOOQ/JAXB-кода и скачивание runtime-зависимостей.
mvn -q generate-sources
mvn -q dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime

rm -rf out
mkdir -p out/src out/tmp

CP="$(find target/lib -name '*.jar' | tr '\n' ':')"

# 2. Компиляция исходников (включая сгенерированные во время generate-sources).
#    Сгенерированный код уже скомпилирован Maven (target/classes); берём его.
rm -rf target/classes
mvn -q compile

# 3. Распаковка каждой внешней библиотеки во временную папку
for lib in target/lib/*.jar; do
  (cd out/tmp && jar xf "$OLDPWD/$lib")
done

# 4. Убрать подписи (иначе SecurityException при запуске)
find out/tmp/META-INF \( -name '*.SF' -o -name '*.DSA' -o -name '*.RSA' \) -delete 2>/dev/null || true
# Наш собственный манифест будет пересоздан ниже.
rm -f out/tmp/META-INF/MANIFEST.MF

# 5. Слияние содержимого библиотек поверх компилированных классов target/classes
  rm -rf out/src
  mkdir -p out/src
  cp -R target/classes/. out/src/
cp -R out/tmp/. out/src/ || true

# 6. Манифест БЕЗ Class-Path
printf 'Main-Class: main.Main\n' > out/manifest.txt

# 7. Сборка jar
jar cfm $PROJECT_NAME.jar out/manifest.txt -C out/src .

# 8. Контроль: библиотеки реально встроены
for marker in io/vertx org/jooq com/fasterxml/jackson org/postgresql jakarta/xml/bind org/glassfish/jaxb com/zaxxer/hikari; do
  COUNT=$(jar tf $PROJECT_NAME.jar | grep -ci "$marker" || true)
  echo "Маркер '$marker': $COUNT классов"
  if [ "$COUNT" -eq 0 ]; then
    echo "ОШИБКА: '$marker' не найден внутри архива"
    rm -rf out
    exit 1
  fi
done

if grep -q '^Class-Path:' out/manifest.txt 2>/dev/null; then
  echo "ОШИБКА: манифест содержит Class-Path"
  rm -rf out
  exit 1
fi

rm -rf out
echo
echo "Собран: $PROJECT_NAME.jar (java -jar $PROJECT_NAME.jar)"