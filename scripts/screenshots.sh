#!/usr/bin/env bash
#
# Dirige o app dentro de um emulador e traz screenshots e vídeo.
#
# Existe por um motivo prático: quem não tem um aparelho Android à mão — ou está
# num iPhone — não consegue instalar um APK. Aqui o emulador roda na nuvem e o
# resultado sai como imagem e vídeo, que qualquer navegador abre.
#
# Roda dentro do reactivecircus/android-emulator-runner, com o emulador já de pé.
set -euo pipefail

APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="${PKG:-com.raulsousa.pulso.debug}"
ACTIVITY="${ACTIVITY:-com.raulsousa.pulso.MainActivity}"
OUT="${OUT:-artefatos}"
VIDEO_REMOTE="/sdcard/pulso.mp4"

mkdir -p "$OUT"

log() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------------------
# Preparo
# ---------------------------------------------------------------------------
log "Aguardando o emulador terminar de subir"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
adb shell input keyevent 82 || true   # desbloqueia a tela

RESOLUTION="$(adb shell wm size | tr -d '\r' | awk -F': ' '{print $2}' | tail -1)"
WIDTH="${RESOLUTION%x*}"
HEIGHT="${RESOLUTION#*x}"
log "Tela: ${WIDTH}x${HEIGHT}"

log "Instalando $APK"
adb install -r -g "$APK"

# A permissão de notificação é concedida por linha de comando para o diálogo do
# sistema não cobrir justamente a primeira tela que queremos fotografar.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# ---------------------------------------------------------------------------
# Navegação
#
# As quatro abas dividem a largura em quatro; o centro de cada uma fica em
# 1/8, 3/8, 5/8 e 7/8 da tela. A barra de navegação fica acima da borda
# inferior, daí o recuo em Y.
# ---------------------------------------------------------------------------
TAB_Y=$(( HEIGHT - 130 ))
tab() {
  local index="$1"
  local x=$(( WIDTH * (2 * index + 1) / 8 ))
  adb shell input tap "$x" "$TAB_Y"
  sleep 3
}

shot() {
  local name="$1"
  adb exec-out screencap -p > "$OUT/$name.png"
  log "capturado: $name.png"
}

log "Abrindo o app"
adb shell am start -n "$PKG/$ACTIVITY"

log "Gravando vídeo em segundo plano"
adb shell screenrecord --time-limit 55 --bit-rate 6000000 "$VIDEO_REMOTE" &
RECORDER=$!

# O modo demonstração anuncia os dispositivos assim que conecta e publica
# telemetria a cada 2 s. Esperamos alguns ciclos para a sparkline ter o que
# desenhar — um gráfico com um ponto só não prova nada.
log "Deixando a casa simulada rodar"
sleep 14
shot "01-dashboard"

log "Abrindo o detalhe de um dispositivo"
adb shell input tap $(( WIDTH / 4 )) $(( HEIGHT / 3 ))
sleep 4
shot "02-detalhe-dispositivo"
adb shell input keyevent KEYCODE_BACK
sleep 2

tab 1; shot "03-automacoes"
tab 2; shot "04-inspetor"
tab 3; shot "05-ajustes"
tab 0

log "Voltando ao dashboard depois de mais telemetria"
sleep 10
shot "06-dashboard-com-historico"

wait "$RECORDER" || true
sleep 2
adb pull "$VIDEO_REMOTE" "$OUT/pulso.mp4" || log "vídeo indisponível (o emulador nem sempre grava)"

# ---------------------------------------------------------------------------
# Diagnóstico: se alguma coisa estourou, o log conta.
# ---------------------------------------------------------------------------
log "Salvando logcat do app"
adb logcat -d > "$OUT/logcat-completo.txt" || true
adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true

if grep -q "FATAL EXCEPTION" "$OUT/logcat-completo.txt" 2>/dev/null; then
  log "ATENÇÃO: houve exceção fatal — veja logcat-crash.txt"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat-completo.txt" | head -60
  exit 1
fi

log "Pronto. Artefatos em $OUT/"
ls -la "$OUT"
