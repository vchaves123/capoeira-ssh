# Roteiro de teste manual — Capoeira SSH

Use este roteiro antes de lançar uma nova versão. Marque cada item com ✅/❌ e anote
observações. Rode pelo menos uma vez no Windows; se possível, repita os itens de
conexão/terminal também no Linux/macOS.

Convenção: **[novo]** marca um caso adicionado por causa de uma mudança recente que
merece atenção redobrada nesta rodada.

---

## 0. Preparação

- [ ] Tenha à mão pelo menos um servidor SSH de teste com **usuário/senha** e, se possível,
      outro com **autenticação por chave privada**.
- [ ] Se for testar o cofre de credenciais pela primeira vez nesta máquina, tenha uma senha
      mestra de teste em mente.
- [ ] Anote a versão/build mostrados no rodapé da aba Home (ex.: `v1.3.1 build #101`) —
      confirme que bate com o que você espera testar.

---

## 1. Janela principal / navegação

- [ ] Abrir o app: a janela abre centralizada, com a aba **Home** selecionada.
- [ ] `Ctrl+N` abre a tela de Nova Sessão.
- [ ] Criar uma sessão e conectar: uma nova aba de terminal abre e recebe foco.
- [ ] `Ctrl+Tab` / `Ctrl+Shift+Tab` alterna entre abas (incluindo voltar para Home).
- [ ] `Ctrl+W` fecha a aba de terminal atual. **[atenção]** Isso fecha **sem pedir
      confirmação**, diferente de clicar no X da própria aba (que pede "Close this
      session?"). Confirme se essa diferença é aceitável — se conectado a um servidor
      importante, `Ctrl+W` desconecta na hora sem chance de cancelar.
- [ ] Tentar fechar a aba **Home** (clique no X, se existir, ou `Ctrl+W` estando nela): não
      deve fechar — ela é permanente.
- [ ] Com 2+ abas de terminal abertas, arrastar uma aba para reordenar: a ordem muda e a
      aba Home continua sempre primeira (não é possível soltar antes dela).
- [ ] Fechar todas as abas de terminal: o app volta sozinho para a aba Home.
- [ ] Fechar a janela principal (X) sem sessões ativas: fecha direto.
- [ ] Fechar a janela principal com 1+ sessões conectadas: aparece confirmação
      ("There are N active sessions..."); cancelar mantém o app aberto, confirmar fecha tudo.

---

## 2. Árvore de sessões (aba Home)

- [ ] Botão **+ Session** cria uma sessão nova (ver seção 3).
- [ ] Botão **+ Group** cria um grupo novo (pede nome).
- [ ] Botão **Import...** abre o diálogo de importação (ver seção 4).
- [ ] Botão **⟳** recarrega a árvore.
- [ ] Menu de contexto (botão direito) em uma **sessão**: Connect, New Session, Rename,
      Duplicate, Edit, Delete — todos habilitados; "New Session in Group" desabilitado.
- [ ] Menu de contexto em um **grupo**: New Session in Group, New Group, Rename Group,
      Delete — habilitados; Rename/Duplicate/Edit (de sessão) desabilitados.
- [ ] Selecionar múltiplos itens (Ctrl+clique ou Shift+clique): menu mostra só as opções
      válidas para seleção múltipla (Delete continua funcionando se todos forem
      sessões/grupos deletáveis).
- [ ] Duplo-clique (ou Enter) numa sessão: conecta.
- [ ] Duplo-clique (ou Enter) num grupo: expande/recolhe.
- [ ] **Rename** (sessão): novo nome aparece na árvore e no título da aba se reconectar.
- [ ] **Rename Group**: grupo muda de nome e todas as sessões dentro dele continuam
      associadas a ele.
- [ ] **Duplicate**: cria uma cópia com sufixo " 2" (ou próximo número livre) e mesmos
      dados de conexão/autenticação/aparência.
- [ ] **Delete** de uma sessão: pede confirmação, remove da árvore.
- [ ] **Delete** de um grupo: pede confirmação ("... and all its sessions?"), remove o
      grupo e todas as sessões dentro dele.
- [ ] Arrastar uma sessão para dentro de um grupo: ela se move para o grupo.
- [ ] Arrastar uma sessão de um grupo para a raiz (fora de qualquer grupo): ela volta a
      ficar solta.
- [ ] Arrastar múltiplas sessões selecionadas ao mesmo tempo para outro grupo: todas se
      movem.
- [ ] Tentar arrastar um grupo para dentro de outro grupo: não deve ser aceito (grupos só
      existem na raiz).
- [ ] Arrastar um grupo para a área vazia da árvore: todas as sessões dele voltam para a
      raiz e o grupo é removido.
- [ ] Fechar e reabrir o app: o estado de expandido/recolhido dos grupos foi preservado
      (a não ser no primeiríssimo carregamento, que abre tudo expandido).

---

## 3. Criar/Editar sessão (diálogo "New/Edit Session")

### 3.1 Campos de conexão
- [ ] Campo **Display name** é opcional (se vazio, usa `usuario@host` como rótulo).
- [ ] **Host** é obrigatório — salvar sem host mostra alerta.
- [ ] **Port** aceita número; se inválido/vazio, assume 22 silenciosamente.
- [ ] **Group**: combo editável — selecionar um grupo existente ou digitar um novo nome;
      "(none)" deixa a sessão na raiz.

### 3.2 Autenticação — combo Username **[novo]**
- [ ] Digitar um nome de usuário livremente no campo Username: funciona como texto normal.
- [ ] Clicar na **seta** do combo Username (não no meio do campo de texto): se o cofre
      existir e estiver trancado, pede a senha mestra **só nesse momento** — clicar/focar
      no meio do texto para digitar **não** deve pedir a senha do cofre.
- [ ] Depois de desbloquear (ou se já estava desbloqueado), a lista mostra as credenciais
      salvas. Selecionar uma: os campos de senha/chave são preenchidos automaticamente e
      ficam **acinzentados/desabilitados** (chave, procurar arquivo, senha).
- [ ] Depois de selecionar uma credencial, editar manualmente o texto do campo Username
      (apagar/digitar algo diferente do nome preenchido): os campos voltam a ficar
      editáveis (o vínculo com a credencial é desfeito).
- [ ] Marcar **"Use private key"**: aparece o campo "Key file:" com botão "…" para
      procurar o arquivo; o rótulo do campo de senha muda para "Passphrase (optional):".
- [ ] Desmarcar "Use private key": campo de chave some, rótulo volta a "Password:".
- [ ] Botão **"…"** (procurar chave): abre o seletor de arquivos do sistema.
- [ ] Botão **"Save Credential…"**: com usuário preenchido, pede um rótulo e salva no
      cofre (pede a senha mestra se ainda não estiver desbloqueado). Depois de salvar, a
      credencial nova já aparece selecionada/vinculada (campos acinzentados).
- [ ] Salvar credencial do tipo **senha** (sem marcar "Use private key"): salva
      usuário+senha.
- [ ] Salvar credencial do tipo **chave** (marcando "Use private key" + arquivo): salva
      usuário+caminho da chave+senha (como passphrase).
- [ ] Tentar salvar sessão sem usuário preenchido (nem digitado, nem credencial
      selecionada): alerta "Username is required."
- [ ] Marcar "Use private key" sem escolher arquivo e salvar: alerta "Key file is
      required."

### 3.3 Botão "Configuration Setting…" **[novo]**
- [ ] Abre o diálogo de configuração (ver seção 5) pré-preenchido com os valores atuais da
      sessão (ou com os padrões globais, se for sessão nova).
- [ ] Ao confirmar (OK) as mudanças no diálogo, e depois salvar a sessão: os valores
      (logging/aparência/tipo de terminal/backspace) são gravados na sessão.
- [ ] Ao cancelar o diálogo de configuração: os valores da sessão não mudam.

### 3.4 Editar sessão existente **[novo — atenção redobrada]**
- [ ] Editar uma sessão comum (usuário/senha manual): os campos vêm pré-preenchidos
      corretamente (senha **não** vem preenchida, por segurança).
- [ ] Editar uma sessão vinculada a uma **credencial salva** com o **cofre já
      desbloqueado**: os campos vêm preenchidos e acinzentados normalmente; salvar sem
      mexer em nada mantém o vínculo.
- [ ] Editar uma sessão vinculada a uma credencial salva com o **cofre trancado**
      (reinicie o app antes): aparece o prompt de senha mestra automaticamente ao abrir o
      diálogo.
  - [ ] Se você **desbloquear** o cofre nesse prompt: os campos vêm preenchidos/acinzentados
        normalmente.
  - [ ] Se você **cancelar** o prompt: os campos ficam vazios/editáveis, mas ao **salvar
        sem alterar nada** (não digitar usuário/senha, não mexer no checkbox de chave), a
        sessão deve **manter o vínculo original com a credencial** (não deve virar
        autenticação manual nem perder o `credentialId`). Confirme conectando depois: deve
        continuar resolvendo a senha do cofre normalmente.
  - [ ] Se, no mesmo cenário (prompt cancelado), você **digitar** um usuário/senha
        manualmente antes de salvar: aí sim a sessão deve virar autenticação manual (isso é
        uma troca deliberada).
- [ ] Salvar a edição sem mudar o host: nome do arquivo/id da sessão não muda.

---

## 4. Importar sessões (PuTTY / MobaXterm)

- [ ] **Scan PuTTY Sessions**: se houver sessões PuTTY salvas no Windows (registro) ou em
      `~/.putty/sessions` (Linux/macOS), elas aparecem na tabela, todas marcadas por
      padrão.
- [ ] Sem sessões PuTTY: mostra "No PuTTY sessions found."
- [ ] **Browse MobaXterm .ini...**: abre seletor de arquivo; escolher um `.ini` válido do
      MobaXterm lista as sessões encontradas (incluindo subpastas de bookmarks, se
      houver).
- [ ] Arquivo `.ini` sem sessões SSH importáveis: mostra "No importable SSH sessions found
      in that file."
- [ ] Desmarcar algumas linhas na tabela antes de importar: só as marcadas são importadas.
- [ ] Clicar **Import Selected** sem nada marcado: alerta pedindo para selecionar ao menos
      uma.
- [ ] Importar com sucesso: mensagem final "Imported N session(s)."; sessões aparecem na
      árvore, nos grupos corretos (ex.: bookmarks do MobaXterm viram grupos).
- [ ] Confirme que **nenhuma senha** foi importada (o hint do diálogo already avisa disso)
      — a sessão importada deve pedir senha na primeira conexão.
- [ ] Escanear PuTTY duas vezes seguidas (ou PuTTY e depois MobaXterm): as linhas se somam
      na tabela em vez de substituir — cuidado para não importar tudo em dobro.
- [ ] Importar uma sessão com nome igual ao de uma sessão já existente: não há
      deduplicação automática aqui (diferente do "Duplicate" da árvore, que soma " 2") —
      confirme que isso é aceitável ou pelo menos não quebra nada.

---

## 5. Diálogo "Configuration Setting" (3 escopos) **[novo]**

Este diálogo é o mesmo em todo lugar; testar o conteúdo uma vez a fundo e depois só
confirmar o **escopo/efeito** nos outros dois lugares.

### 5.1 Conteúdo do diálogo (testar via Home → "Configuration Setting")
- [ ] **Log output**: checkbox "Enable" começa desmarcado (ou conforme valor salvo);
      campo de diretório e nome de arquivo ficam desabilitados até marcar.
- [ ] Marcar "Enable": campos de diretório e nome de arquivo ficam habilitados; o campo
      de nome já vem preenchido no formato `<timestamp>_<nome>.log` (mesmo sem marcar
      Enable, o campo já deve vir preenchido — só habilita/desabilita).
- [ ] Editar o nome do arquivo livremente: acic aceita qualquer texto.
- [ ] Botão "…" do diretório de log: abre seletor de pasta.
- [ ] **Appearance**: mostra as amostras de cor (fg/bg) e o tamanho da fonte atual; botão
      "Customise…" abre o diálogo de aparência (fonte, tamanho, cores, com preview ao
      vivo); "Reset to defaults" nesse sub-diálogo volta aos valores padrão globais.
- [ ] **Terminal type**: combo editável com opções padrão (`xterm-256color`, `xterm`,
      `vt100`, `ansi`, `linux`); aceita digitar um valor customizado.
- [ ] **Backspace key**: combo travado com "DEL (0x7F)" e "BS (0x08) — AIX".
- [ ] **OK** aplica os valores; **Cancel** descarta tudo.

### 5.2 Escopo global (Home → "Configuration Setting")
- [ ] Mudar algo (ex. tipo de terminal) e confirmar: criar uma **sessão nova** depois disso
      deve vir com esse novo valor como padrão.
- [ ] Sessões **já existentes** não são alteradas por essa mudança.

### 5.3 Escopo por sessão (Session dialog → "Configuration Setting…")
- [ ] Mudar algo só numa sessão específica: só essa sessão é afetada; os padrões globais e
      outras sessões continuam com seus próprios valores.

### 5.4 Escopo por aba/terminal (menu de contexto da aba → "Settings…") **[novo]**
- [ ] Com uma sessão conectada, abrir "Settings…" pelo menu de contexto da aba: vem
      pré-preenchido com o estado **atual daquela aba** (fonte/cores/log em andamento).
- [ ] Marcar "Enable" no log e confirmar: o log **começa a ser gravado imediatamente**
      nessa aba (confira o arquivo sendo criado na pasta configurada).
- [ ] Desmarcar "Enable" (estando o log ativo) e confirmar: o log **para** imediatamente.
- [ ] Mudar aparência (fonte/cor) e confirmar: muda **só naquela aba**, na hora, sem
      precisar reconectar.
- [ ] Fechar a aba e reabrir a mesma sessão do zero (nova conexão): as mudanças feitas por
      "Settings…" **não devem ter sido salvas** no arquivo da sessão — a nova aba deve usar
      a aparência/log configurados na sessão salva, não os ajustes temporários da aba
      anterior.

---

## 6. Conectar (diálogo "Connect")

- [ ] Sessão com autenticação **manual (usuário/senha)**: ao conectar, abre "Connect" com
      usuário pré-preenchido e senha vazia; digitar a senha e clicar Connect conecta.
- [ ] **[novo]** Sessão recém-criada com senha digitada na hora da criação: ao conectar
      logo em seguida, a senha já vem **pré-preenchida** — não deve ser pedida de novo.
- [ ] Sessão vinculada a uma **credencial salva** (tipo senha): ao conectar, resolve a
      senha do cofre automaticamente — se o cofre estiver trancado, pede a senha mestra
      antes; se estiver desbloqueado, conecta **sem mostrar o diálogo Connect**.
- [ ] Sessão vinculada a uma **credencial salva do tipo chave**: mesmo comportamento
      acima, mas usa a chave privada e a passphrase do cofre.
- [ ] Sessão com **chave privada digitada manualmente** (sem credencial salva): abre
      "Connect" mostrando "Auth: Private key" e o caminho da chave; campo de passphrase é
      opcional.
- [ ] No diálogo Connect, combo **"Credential"**: '[novo]' se o cofre existir mas estiver
      trancado, **não deve pedir a senha mestra automaticamente** — só ao abrir esse combo.
      Selecionar "-- Enter manually --" volta os campos usuário/senha para edição manual;
      selecionar uma credencial preenche e trava os campos.
- [ ] Cancelar o diálogo Connect: não conecta, volta para a árvore/aba atual; confirme que
      cancelar **não** deixa resíduo (ex.: um usuário digitado no campo antes de cancelar
      não deve ter sido gravado na sessão).
- [ ] Host inválido/inacessível: falha a conexão com mensagem de erro apropriada (sem
      travar a interface).
- [ ] Host com fingerprint desconhecido: pede confirmação antes de conectar (host key
      checking).

---

## 7. Terminal (aba conectada)

- [ ] Comandos básicos funcionam (ex. `ls`, `top`, editor de texto como `vim`/`nano`).
- [ ] Cores ANSI, negrito, sublinhado aparecem corretamente.
- [ ] Buffer alternativo (`vim`, `htop`, `less`) entra e sai limpo, sem sujeira na tela.
- [ ] Scroll do mouse rola o histórico (scrollback) quando não está em modo alt-buffer.
- [ ] Barra de rolagem lateral aparece/funciona quando há histórico.
- [ ] Selecionar texto com o mouse (clique e arrastar) copia para a área de transferência
      automaticamente ao soltar o botão.
- [ ] Duplo-clique numa palavra seleciona a palavra inteira e copia.
- [ ] Botão direito do mouse cola da área de transferência **de uma linha só**: cola
      direto, sem confirmação.
- [ ] **[atenção]** Botão direito do mouse (ou colar via teclado) com **conteúdo de
      múltiplas linhas** na área de transferência: deve aparecer uma **tela de
      confirmação** mostrando o texto completo antes de enviar (proteção contra colar um
      script/comando perigoso sem perceber). Confirmar realmente envia; cancelar não envia
      nada.
- [ ] Redimensionar a janela/aba: o terminal se ajusta ao novo tamanho (PTY resize) sem
      travar.
- [ ] Backspace envia o código certo conforme configurado (DEL padrão; testar BS num
      servidor AIX se tiver um).
- [ ] Teclas de função (F1-F12), Alt+tecla e combinações especiais funcionam sem abrir
      menus do sistema operacional por engano.
- [ ] Deixar uma aba em segundo plano recebendo saída (ex. `tail -f` de um log): o título
      da aba pisca/fica azul em negrito indicando atividade; voltar para a aba limpa o
      indicador.
- [ ] Desconectar o servidor (matar a sessão do lado do servidor, ou desligar a rede): a
      aba passa a mostrar estado "disconnected" (título fica vermelho); clicar na aba
      oferece reconectar.
- [ ] Menu de contexto da aba → **Reconnect** (só aparece quando desconectado): reabre o
      diálogo Connect e reconecta.
- [ ] Menu de contexto da aba → **Rename Tab...**: muda só o título da aba, sem afetar a
      sessão salva.
- [ ] Menu de contexto da aba → **Duplicate Session**: abre uma nova conexão para o mesmo
      host, preservando a aparência atual daquela aba.
- [ ] Menu de contexto da aba → **Start/Stop Logging**: (nota: a versão atual controla log
      só pelo diálogo "Settings…" — confirme que a opção antiga de menu não existe mais e
      que o controle é mesmo via "Settings…", ver 5.4).
- [ ] **[atenção — possível inconsistência]** Menu de contexto da aba → **Close Session**:
      hoje fecha **sem pedir confirmação**, enquanto o botão de fechar (×) da própria aba
      **pede confirmação**. Confirme se esse comportamento é o esperado ou se os dois
      deveriam se comportar igual (avise se achar estranho).
- [ ] Log de sessão habilitado: o arquivo é criado na pasta configurada, sem sequências
      ANSI/escape misturadas no texto (deve ficar plaintext legível).

---

## 8. Gerenciador de Credenciais (cofre)

- [ ] Primeiro uso (sem vault ainda): tentar abrir o Gerenciador de Credenciais pede para
      **criar** a senha mestra (com campo de confirmação).
- [ ] Criar com senhas diferentes nos dois campos: alerta "Passwords do not match."
- [ ] Criar com campo vazio: alerta "Password cannot be empty."
- [ ] Com vault já existente e trancado: pede para **desbloquear** (só um campo de senha).
- [ ] Senha mestra errada: alerta "Wrong password."
- [ ] **New**: cria uma credencial nova — testar os dois tipos (senha simples; usuário +
      chave privada + passphrase).
- [ ] **Edit**: abre uma credencial existente com os campos preenchidos (senha mascarada,
      mas presente); alterar e salvar reflete a mudança.
- [ ] **Delete**: pede confirmação, remove a credencial da lista.
- [ ] Credencial em uso por uma sessão, ao ser **deletada**: a sessão associada, na próxima
      edição, deve tratar isso graciosamente (cai para autenticação manual/pergunta, não
      trava nem apaga a sessão) — ver também item 3.4.
- [ ] Fechar e reabrir o app: o cofre volta ao estado trancado (precisa da senha mestra de
      novo).

---

## 9. Aparência / fontes

- [ ] Diálogo "Customise…" (dentro de Configuration Setting → Appearance): trocar fonte,
      tamanho, cor de texto e cor de fundo atualiza o preview ao vivo.
- [ ] Lista de fontes mostra só fontes monoespaçadas instaladas no sistema.
- [ ] "Reset to defaults": volta para os valores padrão de fábrica (âmbar sobre preto,
      12pt).
- [ ] Aplicar e confirmar: a aparência do terminal realmente muda (fonte/cores) sem precisar
      reiniciar a aba.

---

## 10. Diversos / robustez

- [ ] Tela "About": mostra versão/build/data corretos, créditos de terceiros com links
      clicáveis (abrem o navegador).
- [ ] Link de versão no rodapé da Home: abre a página de release correspondente no GitHub.
- [ ] Aviso de nova versão disponível (se houver uma release mais nova publicada): aparece
      um link "New version available — Download" no rodapé da Home.
- [ ] Redimensionar a janela principal bem pequena e bem grande: os elementos continuam
      usáveis (sem cortar botões essenciais).
- [ ] Testar em pelo menos 2 resoluções/escala de tela diferentes (100% e 150%/200% de
      DPI, se possível) — **[novo]** confirme que clicar na seta do combo de usuário ainda
      abre o cofre corretamente em alta DPI.
- [ ] Encerrar o app à força (Gerenciador de Tarefas) com uma sessão conectada e reabrir:
      nada corrompe (sessões e cofre continuam íntegros).

---

## 11. Só ao testar o pacote "no-JRE" **[novo, se for essa a mudança do release]**

- [ ] Baixar `capoeira-ssh-nojre-multiplatform-<versão>.tar.gz`, extrair.
- [ ] Windows: rodar `run.bat` — o app abre e **nenhuma janela de console fica aberta**
      atrás dele.
- [ ] Sem Java 21+ no PATH: `run.bat`/`run.sh` mostram mensagem de erro clara (não travam
      silenciosamente).
- [ ] Com Java abaixo da versão 21: mensagem de erro clara pedindo Java 21+.
- [ ] (Se tiver acesso a Linux/macOS) `./run.sh` abre o app normalmente, detectando a
      arquitetura certa.

---

## 12. Checklist de release (depois de tudo acima passar)

- [ ] `BuildInfo.BUILD` foi incrementado.
- [ ] `CHANGELOG.md` tem uma entrada para a versão sendo lançada.
- [ ] Versão em `pom.xml` bate com `BuildInfo.VERSION` e com a tag `vX.Y.Z` que será criada.
- [ ] Push da tag disparou o workflow do GitHub Actions e ele terminou **verde**
      (https://github.com/vchaves123/capoeira-ssh/actions).
- [ ] O Release publicado no GitHub tem todos os assets esperados (Windows exe + portable
      zip, Linux deb/rpm/tar.gz, macOS dmg, no-JRE tar.gz) e as release notes corretas.
