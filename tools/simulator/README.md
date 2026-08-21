# Simulador

Uma casa MQTT completa, em um comando.

```bash
docker compose up
```

Sobe:

- **mosquitto** — broker real (`1883` MQTT, `9001` WebSocket)
- **devices** — cinco firmwares fictícios que se anunciam por MQTT Discovery,
  respondem a comandos e publicam telemetria a cada 2 s

## Conectando o app

| De onde | Endereço |
|---|---|
| Emulador Android | `10.0.2.2` porta `1883`, TLS desligado |
| Aparelho na mesma rede | IP da sua máquina, porta `1883` |

Nos ajustes do app, desligue o **modo demonstração** antes.

## Sem Docker

```bash
pip install "paho-mqtt>=2.0"
python devices.py --host localhost --port 1883
```

## O que é simulado

| Dispositivo | Tipo | Tópicos |
|---|---|---|
| Lâmpada da sala | `light` com brilho | `casa/sala/lampada/{estado,set,brilho,brilho/set,disponivel}` |
| Tomada da varanda | `switch` (chaves abreviadas, dialeto Tasmota) | `casa/varanda/tomada/{estado,set}` |
| Temperatura | `sensor` | `casa/sala/clima` → `value_json.temperatura` |
| Umidade | `sensor` | `casa/sala/clima` → `value_json.umidade` |
| Porta de entrada | `binary_sensor` | `casa/entrada/porta` |

Temperatura e umidade compartilham o mesmo tópico e são desambiguadas só pelo
`value_template` — de propósito, porque é assim que firmware real costuma
publicar e é onde parser ingênuo erra.

## Fixture do teste de contrato

O teste `SimulatorContractTest` em `core/mqtt` verifica que o app entende tudo
o que este simulador anuncia. Se você mudar um payload aqui, regenere:

```bash
python tools/simulator/devices.py --dump-discovery \
    > core/mqtt/src/test/resources/simulator-discovery.json
```

A CI compara e falha se estiver desatualizada.

## Testando na unha

```bash
mosquitto_sub -h localhost -t 'casa/#' -v          # ver tudo
mosquitto_pub -h localhost -t casa/sala/lampada/set -m ON
mosquitto_sub -h localhost -t 'homeassistant/#' -v # ver os anúncios
```
