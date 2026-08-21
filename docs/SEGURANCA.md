# Segurança

Um app que aciona coisas físicas dentro da casa de alguém merece um modelo de
ameaças escrito. Este documento diz o que está protegido, o que não está, e onde
o risco real mora — que quase nunca é no app.

## O ponto de partida

O projeto de 2017 tinha o seguinte modelo: **ninguém vai olhar.** Broker em IP
fixo compilado no APK, porta 80, `tcp://` sem TLS, sem autenticação, sem ACL.
Quem estivesse na mesma rede lia todo o tráfego e — pior — publicava nele.
Acender a luz da casa era um comando de terminal.

Isso não é crítica ao autor: era um protótipo acadêmico rodando numa rede
doméstica. Mas é exatamente assim que dispositivos IoT reais são invadidos,
então vale nomear.

## Ativos e ameaças

| Ativo | Ameaça | Mitigação |
|---|---|---|
| Credenciais do broker | Extração do APK, leitura do disco, backup em nuvem | Nunca compiladas; cifradas com chave do Android Keystore; excluídas do backup |
| Comandos (ligar/desligar) | Interceptação e injeção na rede | TLS obrigatório fora da rede local; autenticação no broker |
| Estado da casa (quem está, quando) | Escuta passiva — telemetria revela rotina | TLS; ACL por tópico no broker |
| Controle do broker | Broker anônimo exposto à internet | Documentado abaixo; responsabilidade do operador |
| Aparelho do usuário | App malicioso no mesmo aparelho | Permissões mínimas; `exported=false` no serviço |

## O que o app faz

**TLS por padrão.** `BrokerProfile` nasce com `useTls = true` na porta 8883.
Salvar um perfil sem TLS apontando para fora da rede local mostra um aviso
explícito **antes** de salvar — não numa auditoria seis meses depois.

**Texto puro bloqueado pela plataforma.** `network_security_config.xml` define
`cleartextTrafficPermitted="false"` como base, com exceção nominal apenas para
`localhost`, `127.0.0.1`, `10.0.2.2` e `.local` — onde mora o broker doméstico
e o simulador. Isso é reforçado pelo sistema operacional, não pelo nosso código.

**Senha no Keystore.** `SecretStore` gera uma chave AES-256-GCM dentro do
Android Keystore (respaldada por TEE ou StrongBox nos aparelhos que têm) e
guarda apenas o texto cifrado em `SharedPreferences`. A chave não é exportável
nem por um app com root. Optamos por implementar sobre o Keystore direto em vez
de usar o Jetpack Security (`EncryptedSharedPreferences`), que está
descontinuado — são trinta linhas e elimina uma dependência morta.

**A senha não é serializável.** O campo `password` em `BrokerProfile` é
`@Transient`. Há um teste que falha se ela aparecer no JSON — porque este é o
tipo de regressão que passa despercebida numa revisão de código.

**Backup restrito por inclusão.** `backup_rules.xml` e
`data_extraction_rules.xml` incluem apenas `files/datastore/` — as preferências
do broker. Como a presença de qualquer `<include>` exclui tudo o mais, o
`pulso_secrets.xml` fica de fora por construção, sem depender de um `<exclude>`
que alguém possa remover sem perceber. E é bom que fique de fora: a chave que
decifra aquele arquivo vive no Keystore do aparelho e não viaja no backup, de
modo que o texto cifrado restaurado noutro telefone seria ilegível de qualquer
forma. Preferências voltam num aparelho novo; credenciais, não.

**Permissões mínimas.** Quatro, todas usadas: `INTERNET`,
`ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE(_DATA_SYNC)`, `POST_NOTIFICATIONS`.
As três que o app de 2017 pedia sem usar — inclusive a perigosa
`READ_PHONE_STATE` — foram removidas.

**Falha de autenticação não vira insistência.** Erro de credencial é tratado
como não recuperável: o app não fica retentando. Insistir queima bateria e, num
broker sério, provoca bloqueio do cliente.

**Comando nunca vai retido.** Publicar comando com `retain=true` deixa a ordem
guardada no broker, e ela é reentregue a cada reconexão do dispositivo — uma
lâmpada que acende sozinha toda vez que o ESP reinicia. Há um teste garantindo
que isso não volte.

## O que o app **não** protege

Dito com todas as letras:

- **Aparelho comprometido.** Com root ativo e o app rodando, nada em espaço de
  usuário protege. O Keystore protege a chave em repouso, não o processo vivo.
- **Broker mal configurado.** Se o seu Mosquitto aceita anônimo e está exposto à
  internet, o app estar seguro é irrelevante. Veja abaixo.
- **Dispositivo malicioso na rede.** Um firmware comprometido publicando
  discovery falso pode inserir dispositivos no seu dashboard. O app renderiza o
  que a casa anuncia; ele confia no broker. A defesa é ACL no broker.
- **Certificado de cliente (mTLS)** ainda não é suportado — é o que brokers
  industriais exigem. A estrutura está pronta para receber.
- **Fixação de certificado (pinning)** não está implementada. Para um broker
  próprio, a recomendação é a CA privada em `res/raw` (ver comentário no
  `network_security_config.xml`).

## Configurando o broker direito

O simulador em `tools/simulator/` usa `allow_anonymous true` porque é ambiente
de desenvolvimento e está marcado como tal. Um broker real:

```conf
listener 8883
certfile /mosquitto/certs/server.crt
keyfile  /mosquitto/certs/server.key
require_certificate false

allow_anonymous false
password_file /mosquitto/config/passwd
acl_file      /mosquitto/config/acl
```

E a ACL, que é a parte que quase todo mundo pula:

```conf
# O telefone lê estado e escreve comando. Só isso.
user telefone
topic read  casa/#
topic read  homeassistant/#
topic write casa/+/+/set

# O dispositivo escreve o próprio estado e lê os próprios comandos. Só isso.
user esp-sala
topic write casa/sala/#
topic read  casa/sala/+/set
```

O princípio: **um dispositivo comprometido não deve conseguir comandar os
outros.** Sem ACL, qualquer credencial válida controla a casa inteira — que é o
caminho pelo qual uma lâmpada barata vira ponto de entrada.

## Relatando um problema

Encontrou algo? Abra uma issue descrevendo o impacto, ou fale direto com o autor
se houver risco de exploração ativa.
