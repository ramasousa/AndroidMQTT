# Análise: 2017 → 2026

O que este projeto era, o que estava certo, o que estava errado, o que dava para
usar e o que foi feito. Escrito para ser lido por quem não abriu o código de
2017 — e para ser honesto sobre ele.

---

## 1. O que era

Um app Android de novembro de 2017, feito como projeto de MBA. Duas classes
Java, 160 linhas ao todo:

```java
public class MQTTConstantes {
    public static final String TOPICO_LAMPADA   = "led";
    public static final String MQTT_SERVICE_URI = "tcp://187.191.113.52:80";
}
```

`MainActivity` conectava no broker, assinava `led`, e um `ViewSwitcher` trocava
entre duas imagens (`ligada.jpg` / `desligada.jpg`) conforme chegasse `"1"` ou
`"0"`. Tocar na imagem publicava o valor invertido. Era isso — e funcionava.

Números do projeto original:

| | |
|---|---|
| Linguagem | Java 7 |
| `compileSdk` / `minSdk` / `targetSdk` | 25 / 15 / 25 |
| Gradle / AGP | 2.14.1 / 2.2.3 |
| Repositório de dependências | `jcenter()` |
| Biblioteca MQTT | Eclipse Paho + `paho.android.service` 1.0.2 |
| UI | XML, `RelativeLayout`, `ViewSwitcher` |
| Testes | 2 arquivos gerados, ambos vazios |
| Dispositivos suportados | 1, fixo, compilado |

---

## 2. O que estava bom

Vale começar por aqui, porque é a parte que sobreviveu.

**A escolha do MQTT estava certa, e continua certa.** Em 2017 havia quem
resolvesse isso com HTTP polling ou com a nuvem do fabricante. MQTT é o
protocolo adequado ao problema — pub/sub, leve, com QoS, retenção, last will —
e em 2026 é o padrão de fato de IoT doméstica e industrial. A decisão
arquitetural central do projeto envelheceu bem.

**A separação entre tópico de estado e ação do usuário existia.** O app não
guardava o estado localmente: ele reagia ao que chegava no tópico. Isso é a
mentalidade correta de sistema distribuído, e muita coisa moderna erra aqui.

**Usar `retained` no estado.** O app publicava com `setRetained(true)`, o que
faz o broker guardar o último valor e entregá-lo a quem conectar depois. Para
*estado*, isso está certo e é o que o projeto novo continua fazendo.

**Escopo honesto.** O projeto não fingia ser uma plataforma. Era uma
demonstração de conceito, com uma tela, e cumpria o que prometia.

---

## 3. O que estava ruim

### 3.1 Segurança — os três problemas de uma linha só

```java
"tcp://187.191.113.52:80"
```

1. **`tcp://`, não `ssl://`.** Comando, estado e qualquer credencial trafegavam
   em texto puro. Qualquer um na mesma rede lia e, pior, *publicava* — sem
   autenticação, acender a lâmpada da casa é um comando de uma linha.
2. **Porta 80.** MQTT na porta do HTTP é um truque conhecido para atravessar
   proxy corporativo. Funciona, e é exatamente o padrão de tráfego que um time
   de segurança marca como exfiltração.
3. **IP fixo compilado no APK.** Trocar de broker exigia recompilar. E o
   endereço de um servidor doméstico ficou publicado num repositório aberto —
   ele está no histórico do Git até hoje, o que é irreversível sem reescrever
   a história.

Some a isso um broker sem `allow_anonymous false`, sem usuário, sem ACL de
tópico. O modelo de ameaças era: ninguém vai olhar.

### 3.2 Permissões que o app não usava

```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

`READ_PHONE_STATE` é permissão **perigosa** — dá acesso a identificadores do
aparelho — e não havia nenhuma linha de código que a usasse. Nunca houve
receiver de boot. Permissão não usada é superfície de ataque de graça, motivo de
rejeição na Play Store hoje, e sinal de código copiado de tutorial.

### 3.3 A biblioteca morreu

O `org.eclipse.paho.android.service` foi **arquivado pela Eclipse Foundation**.
Nunca ganhou MQTT 5. Sua arquitetura — uma biblioteca sendo dona de um `Service`
Android com IPC próprio — quebrou com as restrições de execução em background do
Android 8 e virou inviável com os requisitos de *foreground service type* do
Android 14. Este não é um problema de versão: é um beco sem saída.

Junto: **`jcenter()` foi desligado em 2021**. O build de 2017, como está, não
resolve dependências hoje. O projeto estava literalmente sem chão.

### 3.4 A UI mentia

```java
public void alterarStatus(View v) {
    if (switcher.getDisplayedChild() == 0) ligar(); else desligar();
}
```

A imagem trocava porque o usuário tocou, não porque o dispositivo confirmou. Se
o broker estivesse fora, se o ESP tivesse travado, se o Wi-Fi tivesse caído — a
lâmpada na tela acendia do mesmo jeito. E:

```java
@Override
public void connectionLost(Throwable cause) { }
```

Um método vazio. O app perdia a conexão e não contava a ninguém. O usuário
descobria pelo silêncio.

### 3.5 Nada era testável

Os dois arquivos de teste eram os gerados pelo Android Studio, com o
`assertEquals(4, 2 + 2)` intacto. Mas o problema real é anterior: **não havia o
que testar**. Toda a lógica estava dentro de callbacks de MQTT que tocavam a
`View` diretamente, dentro de uma `Activity`. Para testar "o que acontece quando
chega `1` no tópico" era preciso um emulador, um broker e um humano olhando.

### 3.6 Concorrência por sorte

O callback `messageArrived` chega numa thread da biblioteca. O código chamava
`switcher.showPrevious()` direto dali — ou seja, tocava a UI fora da main
thread. Isso *frequentemente* funciona no Android e *ocasionalmente* estoura com
`CalledFromWrongThreadException`. Era um bug latente esperando um timing ruim.

### 3.7 Detalhes que somam

- `compile` em vez de `implementation`: todo o grafo de dependências vazava para
  quem consumisse o módulo, e o build ficava mais lento sem motivo.
- `minifyEnabled false` no release: APK sem encolhimento e sem ofuscação.
- `minSdkVersion 15` (Android 4.0.3): já em 2017 isso cobria fração de um por
  cento dos aparelhos, ao custo de restringir a API disponível.
- Strings de UI escritas direto no XML (`android:text="Ligar"`), sem
  `strings.xml` — sem tradução possível.
- `e.printStackTrace()` como tratamento de erro, quatro vezes.
- Publicação de **comando** com `setRetained(true)`: o broker guarda a última
  ordem e a reentrega a cada reconexão do dispositivo. É a receita para uma
  lâmpada que acende sozinha toda vez que o ESP reinicia — um dos bugs mais
  confusos de depurar em MQTT doméstico.

---

## 4. O que foi feito

| Dimensão | 2017 | 2026 |
|---|---|---|
| Linguagem | Java 7 | Kotlin 2.4 |
| UI | XML + `ViewSwitcher` | Jetpack Compose, Material 3, cor dinâmica |
| Arquitetura | Tudo na `Activity` | 3 módulos; núcleo Kotlin puro sem Android |
| MQTT | Paho 3.1.1 (arquivado) | HiveMQ, MQTT 5, reconexão com backoff |
| Broker | IP fixo compilado, texto puro, porta 80 | Configurável, TLS por padrão, senha no Keystore |
| Dispositivos | 1, compilado | N, descobertos por MQTT Discovery |
| Estado da conexão | Invisível | Visível no topo de toda tela |
| Feedback de comando | Otimista e mentiroso | Otimista com reconciliação e timeout |
| Automação | — | Motor local com histerese e cooldown |
| Depuração | Recompilar com `Log.i` | Inspetor MQTT embutido |
| Primeira execução | Tela morta apontando para um IP que não existe mais | Casa simulada funcionando |
| Testes | 0 reais | 84, rodando em segundos sem emulador |
| CI | — | GitHub Actions: núcleo, contrato e Android |
| Build | Gradle 2.14, `jcenter` | Gradle 8.14, version catalog, Kotlin DSL |
| Permissões | 5, duas sem uso, uma perigosa | 4, todas justificadas |

### As decisões que valem explicação

**Descoberta em vez de configuração.** A mudança conceitual mais importante. Em
2017 o dispositivo conhecido era uma constante compilada. Agora o app não sabe
nada de antemão: ele assina o padrão de discovery e a casa se descreve sozinha.
Isso é o que separa um controle remoto de uma ferramenta.

**Núcleo sem Android.** `:core:domain` e `:core:mqtt` não importam nada da
plataforma. Não é purismo: é o que torna possível testar reconexão, histerese e
reconciliação de estado em milissegundos, e é o que permitiria portar para
desktop ou KMP sem tocar na lógica.

**O broker falso é código de produção, não de teste.** O `FakeMqttEngine`
implementa casamento de wildcards, entrega seletiva e reentrega de retidos —
serve aos testes *e* ao modo demonstração do app. Um app de IoT que abre numa
tela vazia pedindo um IP perde o usuário em dez segundos.

**Injeção de dependência à mão.** Sem Hilt. O grafo cabe numa tela; trocar
depois é mecânico, e começar com framework é pagar processador de anotação e
tempo de build antes de existir problema.

**Teste de contrato entre linguagens.** O simulador Python gera a fixture que o
teste Kotlin consome, e a CI regenera e compara. Contrato entre dois processos
em linguagens diferentes precisa ser verificado, não combinado de boca.

---

## 5. Possibilidades de uso

O app deixou de servir a um caso e passou a servir a uma classe de problemas.

### Doméstico
- **Painel de casa** para quem já tem Home Assistant, Tasmota, ESPHome,
  Zigbee2MQTT ou Shelly — sem instalar o app de cada fabricante.
- **Automação sem nuvem.** As regras rodam no telefone: continuam funcionando
  com a internet caída, desde que o telefone e o broker estejam na mesma rede.
- **Monitoramento de consumo/clima** com histórico local e alerta por limiar.

### Profissional e industrial
- **Bancada e campo.** O inspetor MQTT no bolso substitui abrir o laptop para
  descobrir por que um sensor parou de publicar.
- **Automação predial leve** — iluminação, HVAC, sensores de ocupação — em
  prédios que já falam MQTT/BACnet-gateway.
- **Telemetria de equipamento remoto**: bomba, gerador, câmara fria. Sensor
  publica, telefone do plantonista mostra e a regra de limiar avisa.
- **Agricultura e estufa**: umidade de solo, temperatura, irrigação por regra.
- **Prova de conceito para cliente.** O modo demonstração permite mostrar a
  solução funcionando numa sala de reunião sem levar hardware.

### Educacional
- **Aula de IoT.** `docker compose up` sobe broker e dispositivos; o aluno vê o
  tráfego cru no inspetor enquanto mexe no dashboard. O caminho inteiro do
  protocolo fica visível.
- **Estudo de arquitetura Android moderna**: o repositório é um exemplo
  completo de Compose + módulos + estado imutável + testes de verdade, com um
  antes-e-depois documentado.

### O que este app deliberadamente **não** é
- Não é substituto de Home Assistant: não tem histórico de longo prazo, cenas,
  nem integração com o que não fala MQTT.
- Não é solução multiusuário nem multi-casa.
- Não é gateway: não fala Zigbee, Z-Wave ou BLE direto — isso é papel do
  Zigbee2MQTT e afins.

---

## 6. O que continua em aberto

Honestidade sobre o estado atual:

- **Sem testes instrumentados de UI.** O núcleo tem 84 testes; a camada Compose
  tem zero. Faltam testes de `composeTestRule` para os fluxos principais.
- **Sem edição de automações na interface.** O motor de regras é completo e
  testado, mas a tela só lista e liga/desliga — criar regra ainda exige mexer no
  JSON persistido. É a próxima funcionalidade óbvia.
- **`value_template` simplificado.** Suporta `{{ value_json.chave }}`, que cobre
  a maioria esmagadora dos casos, mas não é um interpretador Jinja.
- **Sem certificado de cliente (mTLS)**, que é o que brokers industriais sérios
  exigem. A estrutura em `BrokerProfile` está pronta para receber.
- **Sem persistência de telemetria.** A série vive em memória e some ao fechar.
  Um Room com retenção configurável resolveria.
- **Versões AndroidX/Compose** foram fixadas na última linha que pude verificar
  no ambiente onde este trabalho foi feito (sem acesso ao Google Maven). Vale
  rodar uma atualização de catálogo no primeiro build local.
- **O IP antigo continua no histórico do Git.** Removê-lo exigiria reescrever a
  história do repositório. Como o endereço já não aponta para nada, o custo de
  reescrever supera o ganho — mas a lição fica: segredo commitado é segredo
  vazado, para sempre.

---

## 7. A lição que o projeto ensina

O código de 2017 não era ruim para o que era. Ele foi vencido por três coisas
que nenhuma revisão de código pegaria na época:

1. **A biblioteca escolhida morreu.** Nenhuma quantidade de código bom protege
   contra uma dependência abandonada — mas uma *fronteira* protege. A diferença
   entre trocar o Paho pelo HiveMQ aqui (um arquivo novo por trás da interface
   `MqttEngine`) e trocar no código de 2017 (reescrever o app) é inteiramente
   sobre onde a fronteira foi desenhada.

2. **A plataforma mudou de regras.** Background, permissões, notificações,
   tipos de serviço em primeiro plano — nada disso existia em 2017 e tudo isso
   quebra código que assume o contrário.

3. **O que não é testável não é mantível.** A lógica estava presa dentro de uma
   `Activity`. Oito anos depois, a única forma de saber se ela ainda funcionava
   era instalar e olhar. Mover o comportamento para funções puras não é elegância
   acadêmica: é o que permite mexer num projeto antigo sem medo.

O resto — Java virou Kotlin, XML virou Compose — é moda, e mudará de novo.
