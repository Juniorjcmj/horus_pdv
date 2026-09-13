namespace HORUSPDV_API.Models.Produtos;

public class VencimentoResumoModel
{
    public int Vencidos { get; set; }
    public int VenceEm7Dias { get; set; }
    public int VenceEm15Dias { get; set; }
    public int VenceEm30Dias { get; set; }
    public int TotalControlados { get; set; }
    public int SemDataInformada { get; set; }
}
