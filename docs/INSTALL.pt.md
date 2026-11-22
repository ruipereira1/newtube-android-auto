# Instalar o NewTube no ecrã do carro

**English:** [INSTALL.md](INSTALL.md)

Este guia é para toda a gente, não é preciso saber nada de técnico. Demora uns 10 minutos.

<p align="center">
  <img src="images/android-auto/car-player.jpg" width="640" alt="Um vídeo a tocar no ecrã do carro, com os botões Anterior, Pausar, Seguinte e Menu em cima">
</p>

> [!WARNING]
> **Só para passageiros.** O condutor nunca deve ver vídeo enquanto conduz.

## O que precisas

- Um **telemóvel Android** (Android 7 ou mais recente).
- Um **carro com Android Auto** (com cabo ou sem fios).
- A app **Android Auto** no telemóvel (a maioria dos telemóveis já a tem).

## Passo 1 — Descarregar a app no telemóvel

1. No telemóvel, abre esta página: **[versão mais recente](https://github.com/ruipereira1/newtube-android-auto/releases/latest)**.
2. Desce até **Assets** e toca em **NewTube-Android-Auto-arm64-v8a.apk**.

   *Telemóvel muito antigo? Escolhe `armeabi-v7a`. Não sabes? Escolhe `universal` (é maior, mas funciona em todos).*

## Passo 2 — Instalar

1. Quando o download acabar, toca no ficheiro para o abrir.
2. Se o telemóvel disser que não pode instalar apps desta origem, toca em **Definições**,
   ativa **Permitir desta origem** e volta atrás.
3. Toca em **Instalar**.
4. Se o **Google Play Protect** mostrar um aviso, toca em **Mais detalhes → Instalar mesmo assim**.

## Passo 3 — Deixar o Android Auto mostrar a app

O Android Auto esconde as apps que não vêm da Google Play, a não ser que as autorizes. Só
fazes isto uma vez.

1. Abre as **Definições** do telemóvel e procura **Android Auto**. Abre.
2. Desce até ao fim e toca **10 vezes** em **Versão**, até aparecer uma mensagem a perguntar se
   queres ativar as definições de programador. Toca em **OK**.
3. Toca no menu **⋮** no canto superior direito e depois em **Definições de programador**.
4. Ativa **Fontes desconhecidas**.

## Passo 4 — Usar no carro

1. Liga o telemóvel ao carro.
2. No ecrã do carro, abre a lista de apps e toca em **NewTube**.
3. Toca em **Menu** e escolhe um vídeo, ou inicia um vídeo no telemóvel. Ele aparece no ecrã
   do carro.

<p align="center">
  <img src="images/android-auto/car-start.png" width="380" alt="Ecrã inicial: inicia um vídeo no telemóvel, ou toca em Menu para escolher um aqui">
  <img src="images/android-auto/car-menu.png" width="380" alt="O menu: Início, Subscrições, Histórico, Playlists e Em alta">
</p>

| Botão | O que faz |
|:--|:--|
| **Anterior / Seguinte** | Vídeo anterior ou seguinte (ou salta 10 segundos, se não houver) |
| **Pausar / Reproduzir** | Pausa ou continua o vídeo |
| **Menu** | Início, Subscrições, Histórico, Playlists, Em alta e o interruptor do modo passageiro |

**No próprio vídeo:**

- **Um toque** mostra os botões. Escondem-se outra vez ao fim de uns 10 segundos.
- **Toque duplo do lado direito** avança 10 segundos; **do lado esquerdo** recua 10 segundos.
  Continua a tocar duas vezes para saltar mais (+20, +30 segundos...).

## O NewTube não aparece no carro?

Em muitos telemóveis, o Android Auto só mostra apps que pareçam vir da Google Play, mesmo
depois do Passo 3. Com um computador resolve-se em dois minutos:

1. No telemóvel, ativa a **depuração USB**:
   **Definições → Acerca do telemóvel →** toca 7 vezes em **Número da compilação**, e depois
   **Definições → Sistema → Opções de programador → Depuração USB**.
2. No computador, abre a
   **[versão mais recente](https://github.com/ruipereira1/newtube-android-auto/releases/latest)**
   e descarrega **NewTube-Android-Auto-computer-installer.zip**. Descomprime-o.
3. Liga o telemóvel ao computador com um cabo USB.
4. **Windows:** faz duplo clique em **Install.bat**.
   **Mac ou Linux:** abre um terminal na pasta e escreve `./install.sh`.
5. No telemóvel, toca em **Permitir** (depuração USB) e depois em **Instalar** / **Atualizar**.

Quando a janela disser **Done**, volta a ligar o telemóvel ao carro. Usa o mesmo instalador em
todas as atualizações.

## Atualizar

Descarrega a versão nova da
[versão mais recente](https://github.com/ruipereira1/newtube-android-auto/releases/latest) e
instala da mesma maneira. Instala por cima da antiga e mantém as tuas definições. (A app não se
atualiza sozinha.)

Tinhas uma versão de teste antiga do NewTube para Android Auto? É uma app separada: depois de
instalares esta, desinstala a antiga para não ficares com dois ícones do NewTube.

## Outros problemas

| Problema | O que fazer |
|:--|:--|
| O ecrã do carro pede para iniciar um vídeo | Toca em **Menu** e escolhe um, ou inicia um vídeo no telemóvel. |
| O carro mostra uma mensagem em vez do vídeo | O modo passageiro está desligado: abre o **Menu** e ativa **Modo passageiro**. |
| Uma lista diz que não foi possível carregar | Confirma que o telemóvel tem internet e abre a lista outra vez. |
| O vídeo para quando o carro anda | O Android Auto limita o que as apps fazem em andamento; depende do carro. |

Continuas com problemas? [Abre um pedido (issue)](https://github.com/ruipereira1/newtube-android-auto/issues)
e diz qual é o teu telemóvel e o teu carro.
