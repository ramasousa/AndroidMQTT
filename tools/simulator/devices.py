#!/usr/bin/env python3
"""Firmwares fictícios que povoam um broker MQTT.

Publica anúncios no formato MQTT Discovery do Home Assistant e depois se
comporta como o hardware faria: responde a comandos, publica telemetria e
mantém tópico de disponibilidade com last will.

O app Pulso — e qualquer outro cliente que fale esse dialeto, inclusive o
próprio Home Assistant — monta a interface sozinho a partir daqui.

    python devices.py --host localhost --port 1883

Requer: paho-mqtt >= 2.0
"""

from __future__ import annotations

import argparse
import json
import logging
import math
import random
import signal
import sys
import threading
import time
from dataclasses import dataclass, field

# Import tardio: `--dump-discovery` gera a fixture do teste de contrato sem
# exigir a biblioteca MQTT instalada (o CI roda isso num runner limpo).
try:
    import paho.mqtt.client as mqtt
except ImportError:  # pragma: no cover
    mqtt = None

DISCOVERY_PREFIX = "homeassistant"
LOG = logging.getLogger("devices")


@dataclass
class Device:
    """Um dispositivo simulado."""

    component: str
    object_id: str
    config: dict
    state_topic: str
    command_topic: str | None = None
    availability_topic: str | None = None
    state: str = "OFF"
    extra: dict = field(default_factory=dict)

    @property
    def discovery_topic(self) -> str:
        return f"{DISCOVERY_PREFIX}/{self.component}/{self.object_id}/config"


def build_devices() -> list[Device]:
    """A mesma casa do modo demonstração do app, agora num broker real."""
    return [
        Device(
            component="light",
            object_id="lampada_sala",
            state_topic="casa/sala/lampada/estado",
            command_topic="casa/sala/lampada/set",
            availability_topic="casa/sala/lampada/disponivel",
            config={
                "name": "Lâmpada da sala",
                "unique_id": "sim-lampada-sala",
                "state_topic": "casa/sala/lampada/estado",
                "command_topic": "casa/sala/lampada/set",
                "brightness_state_topic": "casa/sala/lampada/brilho",
                "brightness_command_topic": "casa/sala/lampada/brilho/set",
                "availability_topic": "casa/sala/lampada/disponivel",
                "device": {
                    "name": "Nó da sala",
                    "identifiers": ["sim-no-sala"],
                    "suggested_area": "Sala",
                    "manufacturer": "Pulso Simulator",
                },
            },
            extra={"brightness": 128},
        ),
        Device(
            component="switch",
            object_id="tomada_varanda",
            state_topic="casa/varanda/tomada/estado",
            command_topic="casa/varanda/tomada/set",
            state="ON",
            config={
                # Chaves abreviadas, como Tasmota e ESPHome publicam.
                "name": "Tomada da varanda",
                "uniq_id": "sim-tomada-varanda",
                "stat_t": "casa/varanda/tomada/estado",
                "cmd_t": "casa/varanda/tomada/set",
                "pl_on": "ON",
                "pl_off": "OFF",
                "dev": {"name": "Nó da varanda", "sa": "Varanda"},
            },
        ),
        Device(
            component="sensor",
            object_id="temperatura_sala",
            state_topic="casa/sala/clima",
            config={
                "name": "Temperatura",
                "unique_id": "sim-temp-sala",
                "state_topic": "casa/sala/clima",
                "unit_of_measurement": "°C",
                "device_class": "temperature",
                "value_template": "{{ value_json.temperatura }}",
                "device": {"name": "Nó da sala", "suggested_area": "Sala"},
            },
        ),
        Device(
            component="sensor",
            object_id="umidade_sala",
            state_topic="casa/sala/clima",
            config={
                "name": "Umidade",
                "unique_id": "sim-umidade-sala",
                "state_topic": "casa/sala/clima",
                "unit_of_measurement": "%",
                "device_class": "humidity",
                "value_template": "{{ value_json.umidade }}",
                "device": {"name": "Nó da sala", "suggested_area": "Sala"},
            },
        ),
        Device(
            component="binary_sensor",
            object_id="porta_entrada",
            state_topic="casa/entrada/porta",
            state="fechada",
            config={
                "name": "Porta de entrada",
                "unique_id": "sim-porta-entrada",
                "state_topic": "casa/entrada/porta",
                "payload_on": "aberta",
                "payload_off": "fechada",
                "device_class": "door",
                "device": {"name": "Nó da entrada", "suggested_area": "Entrada"},
            },
        ),
    ]


class House:
    def __init__(self, host: str, port: int, username: str | None, password: str | None):
        if mqtt is None:
            raise SystemExit("paho-mqtt não instalado: pip install 'paho-mqtt>=2.0'")
        self.devices = build_devices()
        self.by_command = {d.command_topic: d for d in self.devices if d.command_topic}
        self.running = threading.Event()
        self.running.set()

        self.client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"pulso-sim-{random.randint(1000, 9999)}",
        )
        if username:
            self.client.username_pw_set(username, password or "")
        self.client.will_set("casa/sala/lampada/disponivel", "offline", qos=1, retain=True)
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message

        self.host = host
        self.port = port

    # -- ciclo de vida MQTT -------------------------------------------------

    def _on_connect(self, client, _userdata, _flags, reason_code, _properties=None):
        if reason_code != 0:
            LOG.error("Falha ao conectar: %s", reason_code)
            return
        LOG.info("Conectado a %s:%s", self.host, self.port)

        for device in self.devices:
            # Discovery vai retido: quem chegar depois recebe o catálogo inteiro.
            client.publish(
                device.discovery_topic,
                json.dumps(device.config, ensure_ascii=False),
                qos=1,
                retain=True,
            )
            client.publish(device.state_topic, device.state, qos=1, retain=True)
            if device.availability_topic:
                client.publish(device.availability_topic, "online", qos=1, retain=True)
            if "brightness" in device.extra:
                client.publish("casa/sala/lampada/brilho", device.extra["brightness"], qos=1, retain=True)

            if device.command_topic:
                client.subscribe(device.command_topic, qos=1)

        client.subscribe("casa/sala/lampada/brilho/set", qos=1)
        LOG.info("%d dispositivos anunciados", len(self.devices))

    def _on_message(self, client, _userdata, message):
        topic = message.topic
        payload = message.payload.decode("utf-8", errors="replace")
        LOG.info("comando %s <- %s", topic, payload)

        if topic == "casa/sala/lampada/brilho/set":
            brightness = max(0, min(255, int(payload or 0)))
            client.publish("casa/sala/lampada/brilho", brightness, qos=1, retain=True)
            client.publish(
                "casa/sala/lampada/estado",
                "ON" if brightness > 0 else "OFF",
                qos=1,
                retain=True,
            )
            return

        device = self.by_command.get(topic)
        if not device:
            return

        # Um firmware honesto confirma no tópico de estado — é isso que o app
        # espera para sair do estado otimista.
        device.state = payload
        client.publish(device.state_topic, payload, qos=1, retain=True)

    # -- telemetria ---------------------------------------------------------

    def _telemetry_loop(self):
        step = 0
        while self.running.is_set():
            temperatura = round(24.0 + 4.0 * math.sin(step / 18.0) + random.uniform(-0.3, 0.3), 1)
            umidade = round(55.0 + 8.0 * math.sin(step / 27.0) + random.uniform(-1.0, 1.0))
            self.client.publish(
                "casa/sala/clima",
                json.dumps({"temperatura": temperatura, "umidade": umidade}),
                qos=0,
                retain=True,
            )

            if step % 30 == 17:
                self.client.publish("casa/entrada/porta", "aberta", qos=1, retain=True)
            elif step % 30 == 22:
                self.client.publish("casa/entrada/porta", "fechada", qos=1, retain=True)

            step += 1
            time.sleep(2)

    # -- execução -----------------------------------------------------------

    def run(self):
        self.client.connect(self.host, self.port, keepalive=30)
        thread = threading.Thread(target=self._telemetry_loop, daemon=True)
        thread.start()
        self.client.loop_forever()

    def stop(self, *_args):
        LOG.info("Encerrando…")
        self.running.clear()
        for device in self.devices:
            if device.availability_topic:
                self.client.publish(device.availability_topic, "offline", qos=1, retain=True)
        self.client.disconnect()


def dump_discovery() -> str:
    """Despeja os anuncios de discovery, sem conectar em nada.

    Serve de fixture para o teste de contrato em `core/mqtt` — o que o
    simulador publica e o que o app entende sao verificados um contra o outro,
    em vez de combinados de boca. Regenerar:

        python tools/simulator/devices.py --dump-discovery \
            > core/mqtt/src/test/resources/simulator-discovery.json
    """
    payloads = [
        {"topic": d.discovery_topic, "payload": json.dumps(d.config, ensure_ascii=False)}
        for d in build_devices()
    ]
    return json.dumps(payloads, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Simulador de dispositivos MQTT")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    parser.add_argument("--username")
    parser.add_argument("--password")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--dump-discovery",
        action="store_true",
        help="imprime os anuncios de discovery e sai (usado pelo teste de contrato)",
    )
    args = parser.parse_args()

    if args.dump_discovery:
        sys.stdout.write(dump_discovery())
        return 0

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s  %(message)s",
        datefmt="%H:%M:%S",
    )

    house = House(args.host, args.port, args.username, args.password)
    signal.signal(signal.SIGINT, house.stop)
    signal.signal(signal.SIGTERM, house.stop)
    house.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
