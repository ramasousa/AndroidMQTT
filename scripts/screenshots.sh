#!/usr/bin/env bash
#
# Dirige o app dentro de um emulador e traz screenshots, vídeo e os textos de
# cada tela.
#
# Existe por um motivo prático: quem não tem um aparelho Android à mão — ou
# está num iPhone — não consegue instalar um APK. Aqui o emulador roda na
# nuvem e o resultado sai como imagem, vídeo e texto, que qualquer navegador
# abre.
#
# Roda dentro do reactivecircus/android-emulator-runner, com o emulador de pé.
set -euo pipefail

APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
PKG="${PKG:-com.raulsousa.pulso.debug}"
ACTIVITY="${ACTIVITY:-com.raulsousa.pulso.MainActivity}"
OUT="${OUT:-artefatos}"
VIDEO_REMOTE="/sdcard/pulso.mp4"
UI_LOCAL="/tmp/ui.xml"

mkdir -p "$OUT"

log() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------------------
# Leitura da tela
#
# A árvore de acessibilidade é a fonte de verdade sobre o que está na tela.
# Screenshot prova que algo foi desenhado; o despejo prova o quê — e é o que
# permite mirar em elementos por texto em vez de por coordenada. A primeira
# versão deste script tocava em posições calculadas e errava o cartão, o que
# levava o passo seguinte a sair do app sem ninguém perceber.
# ---------------------------------------------------------------------------
despeja_ui() {
  local tentativa
  for tentativa in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; then
      adb shell cat /sdcard/ui.xml 2>/dev/null > "$UI_LOCAL" || true
      [ -s "$UI_LOCAL" ] && return 0
    fi
    sleep 2
  done
  return 1
}

textos_da_tela() {
  tr '>' '\n' < "$UI_LOCAL" \
    | grep -o 'text="[^"]*"' \
    | sed 's/text="//; s/"$//' \
    | grep -v '^$' \
    | sort -u
}

# Toca no centro do elemento que tem exatamente este texto.
toca_texto() {
  local alvo="$1"
  despeja_ui || { log "não consegui ler a tela para tocar em \"$alvo\""; return 1; }

  local bounds
  bounds="$(tr '>' '\n' < "$UI_LOCAL" \
    | grep -F "text=\"$alvo\"" \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    | head -1)"

  if [ -z "$bounds" ]; then
    log "não achei \"$alvo\" na tela"
    return 1
  fi

  local n
  n="$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')"
  # shellcheck disable=SC2086
  set -- $n
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  sleep 3
}

# Se o app saiu de foco — por um BACK a mais, um diálogo do sistema, o que for —
# reabre, em vez de fotografar a tela inicial do Android achando que é o app.
garante_app() {
  local foco
  foco="$(adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' || true)"
  case "$foco" in
    *"$PKG"*) : ;;
    *)
      log "o app não está em foco — reabrindo. Foco atual: $foco"
      adb shell am start -n "$PKG/$ACTIVITY" >/dev/null
      sleep 5
      ;;
  esac
}

shot() {
  local nome="$1"
  garante_app
  adb exec-out screencap -p > "$OUT/$nome.png"
  if despeja_ui; then
    textos_da_tela > "$OUT/$nome.textos.txt"
    echo "--- textos em $nome ---"
    cat "$OUT/$nome.textos.txt"
  fi
  log "capturado: $nome.png"
}

espera_texto() {
  local arquivo="$OUT/$1.textos.txt"
  shift
  local faltando=0 esperado
  for esperado in "$@"; do
    if grep -qiF "$esperado" "$arquivo" 2>/dev/null; then
      echo "  ✓ $esperado"
    else
      echo "  ✗ FALTOU: $esperado"
      faltando=1
    fi
  done
  return "$faltando"
}

# Às vezes o que importa é a tela *não* conter algo — foi assim que se
# descobriu que a aba Casa devolvia o usuário ao detalhe de um dispositivo em
# vez da lista da casa.
recusa_texto() {
  local arquivo="$OUT/$1.textos.txt"
  shift
  local sobrando=0 proibido
  for proibido in "$@"; do
    if grep -qiF "$proibido" "$arquivo" 2>/dev/null; then
      echo "  ✗ NÃO DEVIA ESTAR AQUI: $proibido"
      sobrando=1
    else
      echo "  ✓ sem \"$proibido\""
    fi
  done
  return "$sobrando"
}

# ---------------------------------------------------------------------------
# Preparo
# ---------------------------------------------------------------------------
log "Aguardando o emulador terminar de subir"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
adb shell input keyevent 82 || true   # desbloqueia a tela

log "Instalando $APK"
adb install -r -g "$APK"

# A permissão de notificação é concedida por linha de comando para o diálogo do
# sistema não cobrir justamente a primeira tela que queremos fotografar.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# ---------------------------------------------------------------------------
# Percurso
# ---------------------------------------------------------------------------
log "Abrindo o app"
adb shell am start -n "$PKG/$ACTIVITY"

log "Gravando vídeo em segundo plano"
adb shell screenrecord --time-limit 70 --bit-rate 6000000 "$VIDEO_REMOTE" &
RECORDER=$!

# O modo demonstração anuncia os dispositivos assim que conecta e publica
# telemetria a cada 2 s. Esperamos alguns ciclos para a sparkline ter o que
# desenhar — um gráfico com um ponto só não prova nada.
log "Deixando a casa simulada rodar"
sleep 14
shot "01-dashboard"

log "Abrindo o detalhe da lâmpada"
toca_texto "Lâmpada da sala" || log "seguindo mesmo assim"
shot "02-detalhe-dispositivo"

# A barra inferior continua visível no detalhe, então voltar é tocar na aba —
# nada de BACK, que na tela inicial do app fecha o Pulso.
log "Voltando pela aba Casa"
toca_texto "Casa" || true

for aba in "Automações:03-automacoes" "Inspetor:04-inspetor" "Ajustes:05-ajustes"; do
  log "Abrindo ${aba%%:*}"
  toca_texto "${aba%%:*}" || log "não consegui abrir ${aba%%:*}"
  shot "${aba##*:}"
done

log "Voltando ao dashboard depois de mais telemetria"
toca_texto "Casa" || true
sleep 10
shot "06-dashboard-com-historico"

wait "$RECORDER" || true
sleep 2
adb pull "$VIDEO_REMOTE" "$OUT/pulso.mp4" || log "vídeo indisponível"

# ---------------------------------------------------------------------------
# Diagnóstico
# ---------------------------------------------------------------------------
log "Salvando logcat"
adb logcat -d > "$OUT/logcat-completo.txt" || true
adb logcat -d -b crash > "$OUT/logcat-crash.txt" || true

if grep -q "FATAL EXCEPTION" "$OUT/logcat-completo.txt" 2>/dev/null; then
  log "ATENÇÃO: houve exceção fatal"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat-completo.txt" | head -60
  exit 1
fi

# ---------------------------------------------------------------------------
# Asserções
#
# Sem isto, o job ficaria verde fotografando seis telas em branco — ou, como
# já aconteceu, a tela inicial do Android. O que se afirma aqui é o percurso
# inteiro: os anúncios de MQTT Discovery saíram do broker em memória, viraram
# dispositivos no estado da casa, foram agrupados por cômodo e renderizados.
# ---------------------------------------------------------------------------
FALHAS=0

log "A casa de demonstração apareceu no dashboard?"
espera_texto "01-dashboard" \
  "Lâmpada da sala" "Tomada da varanda" "Temperatura" "Umidade" \
  "Porta de entrada" "Sala" "Varanda" "Entrada" || FALHAS=1

log "O detalhe do dispositivo mostra os tópicos reais?"
espera_texto "02-detalhe-dispositivo" "Tópicos" "estado" "comando" || FALHAS=1

log "As automações abriram?"
espera_texto "03-automacoes" "Nenhuma automação" || FALHAS=1

log "O inspetor abriu?"
espera_texto "04-inspetor" "Filtro" "Publicar" "Assinar" || FALHAS=1

log "Os ajustes abriram?"
espera_texto "05-ajustes" "Broker" "Endereço" "TLS" "Modo demonstração" || FALHAS=1

log "A aba Casa volta para a lista da casa, e não para o último detalhe aberto?"
espera_texto "06-dashboard-com-historico" "Tomada da varanda" "Porta de entrada" || FALHAS=1
recusa_texto "06-dashboard-com-historico" "Tópicos" "Brilho:" || FALHAS=1

log "Artefatos em $OUT/"
ls -la "$OUT"

if [ "$FALHAS" -ne 0 ]; then
  log "O app subiu sem quebrar, mas alguma tela não trouxe o conteúdo esperado."
  exit 1
fi

log "Tudo certo: app instalado, casa descoberta e as cinco telas renderizadas."
