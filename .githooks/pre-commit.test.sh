#!/usr/bin/env bash

set -euo pipefail

hook_source="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/pre-commit"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT

# All index changes and fixture commits stay inside disposable repositories.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null

extended_paths=(
    frontend/src/app/core/alert-charts/alert-chart-extractors.extended.ts
    frontend/src/app/core/alert-messages/alert-message-formatters.extended.ts
    frontend/src/app/core/i18n/translations/en.extended.translations.ts
    frontend/src/app/core/i18n/translations/es-uy.extended.translations.ts
)
custom_directory=backend/alert-templates/src/main/java/app/alertify/alerts/templates/custom
case_number=0
passed=0

new_repository() {
    local path
    case_number=$((case_number + 1))
    repository="$test_root/repository-$case_number"
    git init --quiet "$repository"
    cd -- "$repository"
    git config user.name 'Hook validation'
    git config user.email 'hook-validation@example.invalid'
    git config commit.gpgSign false
    mkdir -p .githooks "$custom_directory"
    cp -- "$hook_source" .githooks/pre-commit
    chmod +x .githooks/pre-commit
    printf '*.java\n' > "$custom_directory/.gitignore"
    printf 'package app.alertify.alerts.templates.custom;\n' > "$custom_directory/package-info.java"
    for path in "${extended_paths[@]}"; do
        mkdir -p -- "$(dirname -- "$path")"
        printf 'export const CUSTOM = {};\n' > "$path"
    done
    printf 'Fixture\n' > README.md
    git add .
    git add -f -- "$custom_directory/package-info.java"
    git commit --quiet --no-gpg-sign -m 'Test fixture'
    git config --local core.hooksPath .githooks
}

stage_change() {
    mkdir -p -- "$(dirname -- "$1")"
    printf '// Local customization\n' >> "$1"
    git add -f -- "$1"
}

check_hook() {
    local expected="$1" description="$2"
    local output status before after
    before="$(git write-tree)"
    if output="$(bash .githooks/pre-commit 2>&1)"; then
        status=0
    else
        status=$?
    fi
    after="$(git write-tree)"
    if [[ "$before" != "$after" ]]; then
        printf 'FAIL: hook changed the index: %s\n' "$description" >&2
        exit 1
    fi
    if [[ "$expected" == blocked ]]; then
        if [[ "$status" != 1 || "$output" != *'WARNING: Commit rejected.'* ]]; then
            printf 'FAIL: expected rejection: %s\n%s\n' "$description" "$output" >&2
            exit 1
        fi
    elif [[ "$status" != 0 ]]; then
        printf 'FAIL: expected acceptance: %s\n%s\n' "$description" "$output" >&2
        exit 1
    fi
    passed=$((passed + 1))
    printf 'PASS: %s\n' "$description"
}

new_repository
check_hook allowed 'clean index'
printf '// Unstaged customization\n' >> "${extended_paths[0]}"
stage_change README.md
check_hook allowed 'ordinary staged file with unstaged customization'

for path in "${extended_paths[@]}"; do
    new_repository
    stage_change "$path"
    check_hook blocked "modified $path"
done

new_repository
git rm --quiet -- "${extended_paths[0]}"
check_hook blocked 'deleted extended file'

new_repository
git mv -- "${extended_paths[0]}" frontend/src/app/core/alert-charts/renamed.ts
check_hook blocked 'extended file renamed out of protected path'

new_repository
git rm --quiet -- "${extended_paths[0]}"
mkdir -p -- "$(dirname -- "${extended_paths[0]}")"
git mv -- README.md "${extended_paths[0]}"
check_hook blocked 'ordinary file renamed into protected path'

for path in "$custom_directory/LocalAlert.java" "$custom_directory/nested/LocalAlert.java" "$custom_directory/nested/Local Alert.java" "$custom_directory/package-info.java"; do
    new_repository
    stage_change "$path"
    check_hook blocked "custom Java file: $path"
done

new_repository
git rm --quiet -- "$custom_directory/package-info.java"
check_hook blocked 'deleted custom Java file'

new_repository
git mv -- "$custom_directory/package-info.java" package-info.java
check_hook blocked 'custom Java file renamed out of protected directory'

new_repository
stage_change "$custom_directory/README.md"
check_hook allowed 'custom directory non-Java documentation'

new_repository
stage_change frontend/other.extended.ts
check_hook allowed 'unrelated file outside the four protected frontend paths'

new_repository
git config --local alertify.allowCustomizations true
for path in "${extended_paths[@]}" "$custom_directory/LocalAlert.java" "$custom_directory/nested/LocalAlert.java" "$custom_directory/package-info.java"; do
    stage_change "$path"
done
check_hook allowed 'fork exception permits extended and custom Java files'
stage_change .env
check_hook blocked 'fork exception keeps environment-file protection'
git reset --quiet HEAD -- .env
stage_change local.pem
check_hook blocked 'fork exception keeps key and certificate protection'
git reset --quiet HEAD -- local.pem
stage_change target/generated.txt
check_hook blocked 'fork exception keeps generated-directory protection'

new_repository
git config --local alertify.allowCustomizations false
stage_change "${extended_paths[0]}"
check_hook blocked 'explicit false restores customization protection'

new_repository
stage_change "${extended_paths[0]}"
head_before="$(git rev-parse HEAD)"
index_before="$(git write-tree)"
if output="$(git commit --no-gpg-sign -m 'Must be rejected' 2>&1)"; then
    printf 'FAIL: actual git commit bypassed the hook\n' >&2
    exit 1
fi
if [[ "$output" != *'WARNING: Commit rejected.'* || "$(git rev-parse HEAD)" != "$head_before" || "$(git write-tree)" != "$index_before" ]]; then
    printf 'FAIL: actual rejected commit did not preserve HEAD and index\n%s\n' "$output" >&2
    exit 1
fi
passed=$((passed + 1))
printf 'PASS: activated hook rejects an actual commit without changing HEAD or index\n'
printf '%s hook checks passed.\n' "$passed"
