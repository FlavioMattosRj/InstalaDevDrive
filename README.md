# InstalaDevDrive

**InstalaDevDrive** é um utilitário Java que cria um [Dev Drive](https://learn.microsoft.com/pt-br/windows/dev-drive/) no Windows 11 de forma segura, controlada e auditável — sem expor o usuário diretamente ao `DISKPART`.

---

## O que é um Dev Drive?

O Dev Drive é um tipo de volume de armazenamento introduzido no Windows 11 (a partir da build 22621.2338) que utiliza o sistema de arquivos **ReFS** com otimizações específicas para cargas de trabalho de desenvolvimento. Ele oferece:

- Cópias de arquivos mais rápidas (Block Cloning do ReFS)
- Menor sobrecarga do antivírus (Microsoft Defender opera em **Performance Mode** no Dev Drive)
- Configurações de filtros de E/S personalizáveis
- Isolamento ideal para repositórios de código, caches de pacotes (npm, Maven, Gradle, pip, NuGet etc.) e artefatos de build

---

## Por que um JAR e não um script DISKPART puro?

O DISKPART opera com scripts de texto sequenciais e não tipados. Um erro de sequência, uma letra de unidade incorreta ou uma linha fora de ordem pode resultar em formatação acidental de um disco físico existente. Este programa resolve isso com:

| Risco do DISKPART puro | Como o InstalaDevDrive mitiga |
|---|---|
| Seleciona disco por número (`select disk N`) | Sempre seleciona por caminho de arquivo (`select vdisk file="..."`) |
| Sem validação prévia dos parâmetros | Valida a letra antes e depois de cada etapa (evidência real via `File.listRoots()`) |
| Sem rollback em caso de falha | Desanexa o VHD e apaga o arquivo incompleto automaticamente em caso de erro |
| Depende do idioma do Windows para interpretar a saída | Nunca confia no texto do DISKPART — confirma sucesso via sistema de arquivos |
| Não verifica se a letra já está em uso | Rejeita letras reservadas (A, B), a unidade do sistema e letras já ocupadas |
| Não requer confirmação explícita | Exige confirmação interativa (ou `--yes`) antes de qualquer alteração |
| VHD não remonta sozinho após reiniciar (workaround comum: tarefa agendada rodando um script `.txt` como SYSTEM, adulterável por quem tiver acesso de escrita à pasta) | Anexa o disco de forma permanente via chamada direta à Windows Virtual Disk API — sem tarefa agendada, sem script em disco |

A formatação final como Dev Drive (ReFS) usa o cmdlet `Format-Volume -DevDrive` do PowerShell (módulo Storage, padrão em qualquer Windows 11) — **não requer o módulo Hyper-V**.

---

## Requisitos

- **Windows 11** build 22621.2338 ou posterior
- **Java 17 ou superior**
- **Execução como Administrador** (obrigatório para criar/formatar volumes)
- Mínimo de **50 GB de espaço livre** no disco que receberá o arquivo `.vhdx`

> **Java 24+**: a partir do JDK 24, o próprio Java passou a avisar quando uma
> biblioteca nativa é carregada por código sem módulo nomeado (JEP 472) —
> caso do [JNA](https://github.com/java-native-access/jna), usado pelo
> pacote `virtdisk`. O manifest do JAR já vem com `Enable-Native-Access:
> ALL-UNNAMED`, então rodar `java -jar InstalaDevDrive.jar` normalmente já
> evita esse aviso, sem precisar de nenhuma flag extra na linha de comando.

---

## Como usar

```
java -jar InstalaDevDrive.jar [opcoes]
```

### Opções

| Opção | Descrição | Padrão |
|---|---|---|
| `--name NOME` | Nome do arquivo `.vhdx` e rótulo da unidade | `DevDrive` |
| `--size TAMANHO` | Tamanho (ex.: `50GB`, `100GB`, `1TB`) | `50GB` |
| `--letter LETRA` | Letra de unidade (ex.: `D`) | Primeira letra livre |
| `--path DIRETÓRIO` | Diretório onde o `.vhdx` será criado | `C:\DevDrive` |
| `--yes` | Não pede confirmação antes de formatar | — |
| `--dry-run` | Mostra o que seria feito, sem executar nada | — |
| `--verbose` | Mostra (em cor diferente) cada comando PowerShell e script DISKPART efetivamente executado | — |
| `--help` | Exibe a ajuda | — |

### Exemplos

```powershell
# Criação padrão (50 GB, primeira letra livre, em C:\DevDrive)
java -jar InstalaDevDrive.jar

# Dev Drive de 100 GB na letra D, arquivo em E:\VHDs\MeuDev.vhdx
java -jar InstalaDevDrive.jar --name MeuDev --size 100GB --letter D --path E:\VHDs

# Simulação sem fazer nada (dry-run)
java -jar InstalaDevDrive.jar --dry-run --name TesteDev --size 60GB

# Modo verboso: imprime cada comando PowerShell e script DISKPART executado, em cor diferente
java -jar InstalaDevDrive.jar --verbose
```

---

## Fluxo de criação

```
1. validatePlan   → Valida letra, caminho, tamanho (sem precisar ser Admin)
2. checkElevation → Confirma execução como Administrador
3. Confirmação    → Pergunta ao usuário (ou aceita --yes)
4. DISKPART       → create vdisk (só cria o arquivo .vhdx, ainda não anexa)
5. VhdxMount      → Anexa o disco de forma PERMANENTE via Virtual Disk API nativa
6. DISKPART       → create partition primary / assign letter (disco já anexado)
7. Verificação    → Confirma que a letra realmente apareceu no sistema de arquivos
8. Format-Volume  → Formata como Dev Drive (ReFS) via PowerShell
9. Verificação    → Confirma o marcador de sucesso na saída do PowerShell
10. Em falha      → Rollback automático (dismount + delete arquivo)
```

### Remontagem automática no boot

A etapa 5 anexa o disco chamando diretamente a **Windows Virtual Disk API**
(`virtdisk.dll`, via [JNA](https://github.com/java-native-access/jna) — pacote
[`br.nom.mattos.flavio.virtdisk`](src/main/java/br/nom/mattos/flavio/virtdisk))
com as flags `ATTACH_VIRTUAL_DISK_FLAG_PERMANENT_LIFETIME` e
`ATTACH_VIRTUAL_DISK_FLAG_AT_BOOT`. O disco fica anexado mesmo depois que este
processo termina e **sobrevive a reinicializações do Windows** — validado
empiricamente.

Isso substitui a abordagem anterior (tarefa agendada rodando, a cada boot como
SYSTEM, um script `.txt` com o comando de reanexação): sem tarefa agendada e
sem nenhum script gravado em disco, não há arquivo mutável que pudesse ser
adulterado para rodar comandos arbitrários como SYSTEM no próximo boot.

Para desfazer a anexação permanente, use `VhdxMount.dismount()` — funciona
mesmo chamado de uma execução diferente da que anexou (é assim que o
`rollback()` do `DevDriveCreator` desfaz uma criação malsucedida).

---

## Build a partir do código-fonte

Requer **Maven 3.6+** e **JDK 17+**.

```powershell
mvn clean package
```

O JAR executável com todas as dependências será gerado em `target/InstalaDevDrive.jar`.

---

## Testes

```powershell
mvn test
```

Os testes cobrem principalmente a **geração dos comandos** de DISKPART e PowerShell
(`DevDriveCreatorTest`, `PowerShellRunnerTest`, `ElevationCheckerTest`) e as validações
que a antecedem (`CommandLineArgsTest`, `SizeParserTest`), sem executar nenhum processo
real — incluindo casos de escaping de aspas no rótulo, tentativas de "injeção" via
`--name`, arredondamento de tamanho e caminhos com espaços.

---

## Estrutura do projeto

```
src/main/java/.../
  instaladevdrive/
    InstalaDevDrive.java      # Ponto de entrada (main)
    cli/
      CommandLineArgs.java    # Parse de argumentos de linha de comando
    core/
      DevDriveCreator.java    # Orquestrador principal
      DiskpartRunner.java     # Execução segura de scripts DISKPART
      PowerShellRunner.java   # Execução de cmdlets PowerShell
      ProcessRunner.java      # Execução genérica de processos externos
      TrustedExecutables.java # Resolve powershell.exe/diskpart.exe por caminho absoluto de System32
      VerboseLog.java         # Log compartilhado do modo --verbose (PowerShell + DISKPART)
      ElevationChecker.java   # Verificação de privilégios de Administrador
      DriveLetterFinder.java  # Busca/validação de letras de unidade
      SizeParser.java         # Parse e validação de tamanhos (50GB, 1TB etc.)
      ProcessResult.java      # Resultado de execução de processo externo
  virtdisk/                   # Binding JNA para a Windows Virtual Disk API (attach nativo/permanente)
    VhdxMount.java            # API publica: mount() / mountPermanently() / dismount()
    VirtDisk.java             # Binding JNA de virtdisk.dll (OpenVirtualDisk/AttachVirtualDisk/DetachVirtualDisk)
    VirtualStorageType.java   # Struct VIRTUAL_STORAGE_TYPE
    OpenVirtualDiskParameters.java     # Struct OPEN_VIRTUAL_DISK_PARAMETERS (v2)
    AttachVirtualDiskParameters.java   # Struct ATTACH_VIRTUAL_DISK_PARAMETERS (v1)
    VirtualDiskException.java # Erro de chamada a virtdisk.dll, com código Win32
src/test/java/.../            # Testes JUnit 5 (mvn test)
```

---

## Licença

Distribuído sob a licença MIT. Veja o arquivo `LICENSE` para detalhes.
