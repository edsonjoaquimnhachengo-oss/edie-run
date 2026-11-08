# Edie Run

Cofre privado para Android, para vídeos, imagens e áudio, com navegador anónimo,
leitor integrado e downloads de vídeos da internet. No telemóvel aparece como
**Calculadora**.

## O que faz

**Disfarce.** O ícone e o nome são de uma calculadora, e ela funciona de verdade.
Para entrar, escreve-se o PIN e carrega-se em `=`.

**Dois cofres.** O PIN principal abre o cofre verdadeiro. O PIN de emergência abre
um segundo cofre, separado, com o mesmo aspecto. Nada no disco indica qual é qual.

**Cifra.** Ficheiros, nomes, miniaturas e definições ficam cifrados com AES-256-GCM.
A chave sai do PIN (PBKDF2-SHA256, 210 000 iterações) e só existe na memória com o
cofre aberto. Os ficheiros ficam na área privada da app: não aparecem na galeria nem
noutras apps, não vão para a nuvem nem para cópias de segurança.

**Fecho automático.** O cofre fecha quando se sai da app, se muda de app ou se
desliga o ecrã. O ecrã do cofre não pode ser fotografado nem gravado, e aparece em
branco na lista de apps recentes.

**Leitor.** Usa o motor do VLC: MP4, MKV, WebM, AVI, MOV, WMV, FLV, TS, MP3, M4A,
FLAC, OGG, entre outros. O vídeo é decifrado em memória, bloco a bloco, e pode-se
saltar para qualquer ponto sem esperar. Tem velocidade (0,5x a 2x), avançar e
recuar 10 s, preencher o ecrã e rodar. Imagens com zoom, GIF e WebP animados.

**Navegador Edie Run.** Sempre em modo privado: sem histórico, sem cache em disco,
sem cookies de terceiros, sem WebRTC (que pode revelar o IP), sem Safe Browsing da
Google, teclado em modo incógnito. Tudo é apagado ao fechar o cofre. Com o **Tor**
ligado, todo o tráfego passa pela rede Tor e a navegação fica bloqueada até a ligação
estar confirmada, para o IP real nunca sair.

**Downloads.** Usa o yt-dlp: YouTube, Facebook, Instagram, TikTok, X, Vimeo,
Dailymotion, Reddit e centenas de outros sites. Também guarda imagens (toque longo
no navegador) e ficheiros directos. O vídeo é cifrado para o cofre e o ficheiro
temporário é apagado. Pode partilhar um link a partir do YouTube ou do TikTok para
a "Calculadora". Os downloads continuam com o cofre fechado, com uma notificação
neutra ("A processar…").

## Compilar no GitHub (sem computador)

1. Crie um repositório **privado** no GitHub e envie para lá esta pasta inteira
   (a pasta `app/` inclui a chave de assinatura `edierun.jks`, por isso o
   repositório tem de ser privado).
2. Cada envio para o ramo `main` dispara a compilação. Também pode ir a
   **Actions › Compilar APK do Edie Run › Run workflow**.
3. Ao fim de 10 a 15 minutos aparece uma **Release** com dois ficheiros:
   - `EdieRun-arm64.apk`: para quase todos os telemóveis;
   - `EdieRun-arm32.apk`: só para telemóveis antigos.
4. Abra a Release no telemóvel, descarregue o APK e instale (o Android pede
   autorização para instalar apps de fora da Play Store).

A chave de assinatura é sempre a mesma, por isso as versões novas instalam-se por
cima sem perder o cofre. **Não desinstale a app**: desinstalar apaga o cofre.

## Primeira utilização

1. Abra a "Calculadora". Na primeira vez aparece a configuração.
2. Crie o PIN principal e o PIN de emergência (4 a 12 algarismos, diferentes).
3. Daí em diante: escreva o PIN na calculadora e carregue em `=`.

Não há recuperação de PIN. Se o esquecer, o conteúdo fica inacessível para sempre.

## Limites, com franqueza

- O Android sabe que existe uma app chamada Calculadora, quanto tempo foi usada e
  quanto espaço ocupa. Um técnico com o telemóvel desbloqueado vê que a app guarda
  muitos dados, mas não consegue lê-los sem o PIN.
- Exportar um ficheiro tira-o do cofre: essa cópia fica sem cifra.
- Ao importar da galeria, a app oferece-se para apagar os originais. Em memória
  flash, apagar não garante destruição física; por isso o melhor é trazer o conteúdo
  directamente para o cofre (downloads e navegador), sem passar pela galeria.
- O YouTube às vezes bloqueia downloads feitos através do Tor. Se acontecer,
  desligue o Tor para esse download.
- Os sites mudam. Se um deixar de funcionar, use **Downloads › Actualizar motor**.
- Descarregar conteúdo protegido por direitos de autor, ou contra as regras de cada
  plataforma, é responsabilidade de quem usa a app.

## Componentes

- yt-dlp via `youtubedl-android` (GPL-3.0)
- LibVLC para Android (LGPL-2.1)
- Tor via `tor-android` do Guardian Project (BSD)
- Jetpack Compose, AndroidX WebKit
