# Android Auto no NewTube

Este fork adiciona um ecrã de **Android Auto** ao NewTube. O telemóvel continua a ser o leitor;
o carro recebe a imagem do vídeo que já está a passar no telemóvel e alguns controlos.

> [!WARNING]
> **Só para passageiros.** O condutor não deve ver vídeo enquanto conduz. A app é experimental
> e instalada manualmente; não é afiliada com a Google, Android Auto, YouTube, NewTube ou
> SmartTube. Usa por tua conta e risco e cumpre a lei.

## O que precisas

- Telemóvel Android 7.0 (API 24) ou mais recente.
- A app **Android Auto** instalada no telemóvel.
- Carro ou rádio com **Android Auto projetado**. Testado num Toyota Yaris 2025.
- Cabo USB de dados (ou Android Auto sem fios, se o carro e o telemóvel suportarem).
- APK instalado manualmente (sideload). Não está na Play Store.
- Para compilar: JDK 17+ (testado com 21), Android SDK 37 e os submódulos.

## Instalar

1. Descarrega o APK da
   [versão mais recente](https://github.com/ruipereira1/newtube-android-auto/releases/latest)
   (na maioria dos telemóveis é o ficheiro **arm64-v8a**), ou compila-o (ver [Compilar](#compilar)).
2. No telemóvel, autoriza a instalação de apps de fontes desconhecidas para a app que vais usar
   para abrir o APK.
3. Instala o APK.
4. Abre o **Android Auto**, ativa o **modo de programador** (toca dez vezes na versão) e em
   **Definições de programador** ativa **Fontes desconhecidas**.
5. Liga o telemóvel ao carro e abre o **NewTube** no launcher do Android Auto.
6. Toca em **Menu** no ecrã do carro e escolhe um vídeo (Início, Subscrições, Histórico,
   Playlists ou Em alta), ou inicia um vídeo no telemóvel. Ele aparece no ecrã do carro.
7. Usa os botões no topo do ecrã do carro: anterior, play/pause, seguinte e Menu.

O modo passageiro liga-se e desliga-se no fundo do **Menu** do carro, ou no telemóvel em
**Definições → Android Auto passenger mode**.

## Modo passageiro

O modo passageiro vem **ligado por omissão**. É ele que permite mostrar vídeo no ecrã do carro
com o carro em andamento.

- **Ligado:** o carro mostra o vídeo do telemóvel.
- **Desligado:** o carro mostra uma mensagem em vez de vídeo.

Podes desligá-lo no ecrã do carro (último botão da barra) ou nas definições do telemóvel. A
preferência é a mesma nos dois sítios.

## Compilar

```bash
git clone --recurse-submodules https://github.com/ruipereira1/newtube-android-auto.git
cd newtube-android-auto
echo "sdk.dir=/caminho/para/Android/Sdk" > local.properties
./gradlew :smarttubetv:assembleStmobileDebug
```

Se os submódulos não vierem no clone:

```bash
git submodule update --init --recursive
```

O APK fica em:

```text
smarttubetv/build/outputs/apk/stmobile/debug/
smarttubetv/build/outputs/renamed_apks/stmobileDebug/
```

## Testar

```bash
./gradlew :smarttubetv:testStmobileDebugUnitTest \
  --tests 'com.newtube.mobile.car.CarModePrefsTest'
```

O teste verifica que o modo passageiro vem ligado e que pode ser desligado e ligado outra vez.
Não precisa de carro nem de telemóvel.

## Problemas comuns

### O NewTube não aparece no launcher do Android Auto

- Ativa o **modo de programador** e as **fontes desconhecidas** no Android Auto.
- Confirma que o APK está instalado no mesmo utilizador que está ligado ao carro.
- Reinicia a app Android Auto e volta a ligar o cabo.
- Usa um cabo USB de dados, não um cabo só de carga.
- Confirma que o carro suporta Android Auto projetado.

### A app abre com erro

- Se o registo disser `Min API level not declared in manifest`, o APK é anterior à correção.
  Instala uma build feita com o código atual.
- Confirma que o APK tem as permissões `androidx.car.app.ACCESS_SURFACE` e
  `androidx.car.app.NAVIGATION_TEMPLATES`. Sem a segunda, o `NavigationTemplate` é rejeitado.
- Atualiza o Android Auto no telemóvel.
- Captura o erro:

```bash
adb logcat -c
# abre a app no Android Auto e reproduz o erro
adb logcat -d | grep -a -iE "AndroidRuntime|app.smarttube|newtube|carapp|FATAL|Exception"
```

### O telemóvel mostra vídeo mas o carro fica preto

- Inicia o vídeo no telemóvel primeiro; o carro segue o leitor do telemóvel.
- Confirma que o modo passageiro está ligado.
- Alguns carros e versões do Android Auto não dão surface a apps de terceiros.
- Toca uma vez no ecrã do carro; alguns hosts só entregam gestos depois do primeiro toque.

### Os controlos do carro não fazem nada

- A barra de ações só existe quando há um leitor ativo.
- Alguns carros não encaminham toques nem gestos; usa os botões da barra.
- Se o carro esconder a barra em andamento, usa o telemóvel para essa ação.

### O APK não instala

Se o Android disser que há um conflito de assinatura, desinstala a versão anterior do NewTube
primeiro. As builds de debug são assinadas com uma chave local, que pode mudar de máquina para
máquina.

## Limitações

- Experimental. Foi testado num Toyota Yaris 2025 e num telemóvel.
- O `NavigationTemplate` é um workaround para obter uma surface; não é uma categoria de vídeo
  suportada. A Google ou o fabricante do carro podem restringir.
- O carro é um segundo ecrã do leitor do telemóvel, não um leitor independente. O telemóvel tem
  de ficar ligado e o leitor tem de continuar vivo.
- O vídeo enche o ecrã do carro sem ficar esticado. Quando o ecrã é mais largo do que o vídeo,
  corta-se uma faixa fina em cima e em baixo da imagem.
- O menu do carro mostra as linhas que o Android Auto permitir; em andamento podem ser menos.
- Alguns carros e versões do Android Auto não expõem uma surface a apps de terceiros.
- O modo passageiro é uma preferência, não uma autorização legal. O condutor é responsável por
  não ver vídeo enquanto conduz.
- Só sideload; não há release na Play Store.

## Mais detalhes

A documentação técnica em inglês está em [ANDROID_AUTO.md](ANDROID_AUTO.md).

## Créditos e licença

Este fork usa o trabalho do [NewTube](https://github.com/aleixrodriala/newtube) e do
[SmartTube](https://github.com/yuliskov/SmartTube). O README original do NewTube está em
[README.upstream.md](../README.upstream.md). Licença [MIT](../LICENSE).
