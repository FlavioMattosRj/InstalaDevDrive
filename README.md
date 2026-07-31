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

A formatação final como Dev Drive (ReFS) usa o cmdlet `Format-Volume -DevDrive` do PowerShell (módulo Storage, padrão em qualquer Windows 11) — **não requer o módulo Hyper-V**.

---

## Requisitos

- **Windows 11** build 22621.2338 ou posterior
- **Java 8 ou superior** (apenas JRE, sem instalação de dependências adicionais)
- **Execução como Administrador** (obrigatório para criar/formatar volumes)
- Mínimo de **50 GB de espaço livre** no disco que receberá o arquivo `.vhdx`

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
| `--path DIRETÓRIO` | Diretório onde o `.vhdx` será criado | `%USERPROFILE%\DevDrive` |
| `--yes` | Não pede confirmação antes de formatar | — |
| `--dry-run` | Mostra o que seria feito, sem executar nada | — |
| `--help` | Exibe a ajuda | — |

### Exemplos

```powershell
# Criação padrão (50 GB, primeira letra livre, em %USERPROFILE%\DevDrive)
java -jar InstalaDevDrive.jar

# Dev Drive de 100 GB na letra D, arquivo em E:\VHDs\MeuDev.vhdx
java -jar InstalaDevDrive.jar --name MeuDev --size 100GB --letter D --path E:\VHDs

# Simulação sem fazer nada (dry-run)
java -jar InstalaDevDrive.jar --dry-run --name TesteDev --size 60GB
```

---

## Fluxo de criação

```
1. validatePlan  → Valida letra, caminho, tamanho (sem precisar ser Admin)
2. checkElevation → Confirma execução como Administrador
3. Confirmação    → Pergunta ao usuário (ou aceita --yes)
4. DISKPART       → create vdisk / attach / create partition / assign letter
5. Verificação    → Confirma que a letra realmente apareceu no sistema de arquivos
6. Format-Volume  → Formata como Dev Drive (ReFS) via PowerShell
7. Verificação    → Confirma FORMAT_OK
8. Em falha       → Rollback automático (detach vdisk + delete arquivo)
```

---

## Build a partir do código-fonte

Requer **Maven 3.6+** e **JDK 8+**.

```powershell
mvn clean package
```

O JAR executável com todas as dependências será gerado em `target/InstalaDevDrive.jar`.

---

## Estrutura do projeto

```
src/main/java/.../
  InstalaDevDrive.java        # Ponto de entrada (main)
  cli/
    CommandLineArgs.java      # Parse de argumentos de linha de comando
  core/
    DevDriveCreator.java      # Orquestrador principal
    DiskpartRunner.java       # Execução segura de scripts DISKPART
    PowerShellRunner.java     # Execução de cmdlets PowerShell
    ProcessRunner.java        # Execução genérica de processos externos
    ElevationChecker.java     # Verificação de privilégios de Administrador
    DriveLetterFinder.java    # Busca/validação de letras de unidade
    SizeParser.java           # Parse e validação de tamanhos (50GB, 1TB etc.)
    ProcessResult.java        # Resultado de execução de processo externo
```

---

## Licença

Distribuído sob a licença MIT. Veja o arquivo `LICENSE` para detalhes.
