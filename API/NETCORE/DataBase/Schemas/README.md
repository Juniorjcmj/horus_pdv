# Schemas XSD da NF-e/NFC-e

257 arquivos `.xsd` da NF-e/NFC-e v4.00, incluindo o grupo IBS/CBS/IS da reforma tributária
(`IBSCBS`, `IBSCBSTot` em `leiauteNFe_v4.00.xsd`) — usados pelo `ZeusFiscalProvider`
(`ConfiguracaoServico.DiretorioSchemas`, com `IsValidaSchemas = true` quando esta pasta tem
arquivos).

**Origem:** pasta `NFe.AppTeste/Schemas` do repositório
[Hercules-NET/ZeusFiscal](https://github.com/Hercules-NET/ZeusFiscal) (branch `master`) — o
mesmo fork cujo pacote NuGet (`Hercules.NET.NFe.NFCe`) o projeto usa, o que garante que a
versão dos schemas bate com a versão da lib. Copiados em 2026-09-06.

*(O repositório também tem uma pasta `Schemas-sem-vIBS`, sem o grupo IBS/CBS — não é essa
que foi usada aqui.)*

**Manutenção:** ao atualizar a versão do pacote `Hercules.NET.NFe.NFCe` no `.csproj` por
causa de uma Nota Técnica nova, baixe de novo essa mesma pasta do repositório (na tag/branch
correspondente à versão do pacote) e substitua os arquivos aqui — não é automático, e
schemas desatualizados podem gerar rejeição de validação local mesmo com uma nota válida.
