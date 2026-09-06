/**
 * Arquivo: src/pages/Admin/MyCompanyPage.tsx
 * Objetivo: centraliza configurações cadastrais e de contato da empresa.
 * Entradas esperadas: não recebe props; renderiza formulário de dados institucionais.
 */
import { useEffect, useState } from "react";
import PageHeader from "@/components/Admin/PageHeader";
import { SearchableSelectField, YesNoSegmentedControl } from "@/components/Form";
import LoadingButton from "@/components/Loading/LoadingButton";
import { Toast } from "@/hooks/Dialog";
import useInputMasks from "@/hooks/InputMasks/useInputMasks";
import PageLayout from "@/layout/PageLayout";
import { companyService } from "@/services/api/companyService";
import { lookupAddressByCep, sanitizeCep } from "@/utils/cepLookup";
import { isValidEmail } from "@/utils/validators";

const UF_OPTIONS = [
  "AC",
  "AL",
  "AP",
  "AM",
  "BA",
  "CE",
  "DF",
  "ES",
  "GO",
  "MA",
  "MT",
  "MS",
  "MG",
  "PA",
  "PB",
  "PR",
  "PE",
  "PI",
  "RJ",
  "RN",
  "RS",
  "RO",
  "RR",
  "SC",
  "SP",
  "SE",
  "TO",
] as const;

const UF_SELECT_OPTIONS = UF_OPTIONS.map((option) => ({ value: option, label: option }));

const CRT_OPTIONS = [
  { value: 1, label: "1 - Simples Nacional" },
  { value: 2, label: "2 - Simples Nacional, excesso de sublimite" },
  { value: 3, label: "3 - Regime Normal" },
  { value: 4, label: "4 - MEI" },
];

function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : "";
      // O input type="file" lê como data URL ("data:application/x-pkcs12;base64,AAAA...");
      // só a parte depois da vírgula interessa para o backend.
      resolve(result.split(",").pop() ?? "");
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

export default function MyCompanyPage() {
  const { maskCep, maskCnpj, maskPhoneBr, onlyDigits } = useInputMasks();

  const [fantasyName, setFantasyName] = useState("");
  const [corporateName, setCorporateName] = useState("");
  const [cnpj, setCnpj] = useState("");
  const [stateRegistration, setStateRegistration] = useState("");
  const [website, setWebsite] = useState("");
  const [email, setEmail] = useState("");
  const [telSac, setTelSac] = useState("");
  const [telefone, setTelefone] = useState("");
  const [celular, setCelular] = useState("");
  const [cep, setCep] = useState("");
  const [address, setAddress] = useState("");
  const [number, setNumber] = useState("");
  const [neighborhood, setNeighborhood] = useState("");
  const [city, setCity] = useState("");
  const [uf, setUf] = useState("SP");
  const [complement, setComplement] = useState("");
  const [emailSmtpEnabled, setEmailSmtpEnabled] = useState(false);
  const [emailSmtpHost, setEmailSmtpHost] = useState("smtp-mail.outlook.com");
  const [emailSmtpPort, setEmailSmtpPort] = useState("587");
  const [emailSmtpEnableSsl, setEmailSmtpEnableSsl] = useState(true);
  const [emailSmtpUser, setEmailSmtpUser] = useState("");
  const [emailSmtpPassword, setEmailSmtpPassword] = useState("");
  const [emailSmtpHasPassword, setEmailSmtpHasPassword] = useState(false);
  const [emailSmtpFromEmail, setEmailSmtpFromEmail] = useState("");
  const [emailSmtpFromName, setEmailSmtpFromName] = useState("Hórus PDV");
  const [emailSmtpReplyTo, setEmailSmtpReplyTo] = useState("");
  const [cepLookupLoading, setCepLookupLoading] = useState(false);
  const [cepLookupError, setCepLookupError] = useState("");
  const [saving, setSaving] = useState(false);

  // Dados fiscais (emitente NFC-e modelo 65)
  const [crt, setCrt] = useState(1);
  const [cnaeFiscal, setCnaeFiscal] = useState("");
  const [codigoMunicipioIbge, setCodigoMunicipioIbge] = useState("3304557");
  const [ambienteFiscal, setAmbienteFiscal] = useState(2);
  const [cscId, setCscId] = useState("");
  const [csc, setCsc] = useState("");
  const [cscHasValue, setCscHasValue] = useState(false);
  const [certificadoPfxBase64, setCertificadoPfxBase64] = useState("");
  const [certificadoSenha, setCertificadoSenha] = useState("");
  const [certificadoFileName, setCertificadoFileName] = useState("");
  const [certificadoHasValue, setCertificadoHasValue] = useState(false);
  const [certificadoThumbprint, setCertificadoThumbprint] = useState("");
  const [certificadoValidoAte, setCertificadoValidoAte] = useState<string | null>(null);
  const [respTecCnpj, setRespTecCnpj] = useState("");
  const [respTecContato, setRespTecContato] = useState("");
  const [respTecEmail, setRespTecEmail] = useState("");
  const [respTecFone, setRespTecFone] = useState("");

  useEffect(() => {
    companyService
      .get()
      .then((data) => {
        if (!data) return;
        setFantasyName(data.fantasyName);
        setCorporateName(data.corporateName);
        setCnpj(data.cnpj);
        setStateRegistration(data.stateRegistration);
        setWebsite(data.website);
        setEmail(data.email);
        setTelSac(data.sacPhone);
        setTelefone(data.phone);
        setCelular(data.mobile);
        setCep(data.cep);
        setAddress(data.address);
        setNumber(data.number);
        setNeighborhood(data.neighborhood);
        setCity(data.city);
        setUf(data.uf || "SP");
        setComplement(data.complement);
        setEmailSmtpEnabled(Boolean(data.emailSmtpEnabled));
        setEmailSmtpHost(data.emailSmtpHost || "smtp-mail.outlook.com");
        setEmailSmtpPort(String(data.emailSmtpPort || 587));
        setEmailSmtpEnableSsl(data.emailSmtpEnableSsl ?? true);
        setEmailSmtpUser(data.emailSmtpUser || "");
        setEmailSmtpPassword("");
        setEmailSmtpHasPassword(Boolean(data.emailSmtpHasPassword));
        setEmailSmtpFromEmail(data.emailSmtpFromEmail || data.email || "");
        setEmailSmtpFromName(data.emailSmtpFromName || data.fantasyName || "Hórus PDV");
        setEmailSmtpReplyTo(data.emailSmtpReplyTo || "");
        setCrt(data.crt || 1);
        setCnaeFiscal(data.cnaeFiscal || "");
        setCodigoMunicipioIbge(data.codigoMunicipioIbge || "3304557");
        setAmbienteFiscal(data.ambienteFiscal || 2);
        setCscId(data.cscId || "");
        setCsc("");
        setCscHasValue(Boolean(data.cscHasValue));
        setCertificadoPfxBase64("");
        setCertificadoSenha("");
        setCertificadoFileName("");
        setCertificadoHasValue(Boolean(data.certificadoHasValue));
        setCertificadoThumbprint(data.certificadoThumbprint || "");
        setCertificadoValidoAte(data.certificadoValidoAte);
        setRespTecCnpj(data.respTecCnpj || "");
        setRespTecContato(data.respTecContato || "");
        setRespTecEmail(data.respTecEmail || "");
        setRespTecFone(data.respTecFone || "");
      })
      .catch(() => {
        Toast.error("Não foi possível carregar dados da empresa.");
      });
  }, []);

  const handleCertificateFile = async (file: File | null) => {
    if (!file) return;
    if (!file.name.toLowerCase().endsWith(".pfx") && !file.name.toLowerCase().endsWith(".p12")) {
      Toast.error("Selecione um arquivo de certificado .pfx ou .p12.");
      return;
    }
    setCertificadoPfxBase64(await fileToBase64(file));
    setCertificadoFileName(file.name);
  };

  const handleCepLookup = async () => {
    const rawCep = sanitizeCep(cep);

    if (rawCep.length === 0) {
      setCepLookupError("");
      return;
    }

    if (rawCep.length !== 8) {
      setCepLookupError("CEP inválido. Informe 8 dígitos.");
      return;
    }

    setCepLookupLoading(true);
    setCepLookupError("");

    const result = await lookupAddressByCep(rawCep);

    setCepLookupLoading(false);

    if (!result.success) {
      setCepLookupError("Verifique o CEP e tente novamente.");
      return;
    }

    setCep(maskCep(result.data.cep || rawCep));
    setAddress(result.data.endereco || "");
    setNeighborhood(result.data.bairro || "");
    setCity(result.data.cidade || "");
    if (result.data.estado) setUf(result.data.estado);
    setComplement(result.data.complemento || "");
  };

  const saveCompany = async () => {
    if (fantasyName.trim().length < 3) {
      Toast.error("Informe o nome fantasia.");
      return;
    }

    if (onlyDigits(cnpj).length !== 14) {
      Toast.error("CNPJ inválido.");
      return;
    }

    if (emailSmtpEnabled) {
      const smtpPort = Number(emailSmtpPort);

      if (!emailSmtpHost.trim()) {
        Toast.error("Informe o host SMTP.");
        return;
      }

      if (!Number.isInteger(smtpPort) || smtpPort < 1 || smtpPort > 65535) {
        Toast.error("Informe uma porta SMTP válida.");
        return;
      }

      if (!isValidEmail(emailSmtpUser)) {
        Toast.error("Informe o usuário SMTP.");
        return;
      }

      if (!isValidEmail(emailSmtpFromEmail)) {
        Toast.error("Informe o e-mail remetente.");
        return;
      }

      if (emailSmtpReplyTo.trim() && !isValidEmail(emailSmtpReplyTo)) {
        Toast.error("Informe um e-mail de resposta válido.");
        return;
      }

      if (!emailSmtpHasPassword && !emailSmtpPassword.trim()) {
        Toast.error("Informe a senha de app SMTP.");
        return;
      }
    }

    if (certificadoPfxBase64 && !certificadoSenha.trim()) {
      Toast.error("Informe a senha do certificado digital enviado.");
      return;
    }

    setSaving(true);
    try {
      const data = await companyService.update({
        fantasyName,
        corporateName,
        cnpj,
        stateRegistration,
        website,
        email,
        sacPhone: telSac,
        phone: telefone,
        mobile: celular,
        cep,
        address,
        number,
        neighborhood,
        city,
        uf,
        complement,
        emailSmtpEnabled,
        emailSmtpHost,
        emailSmtpPort: Number(emailSmtpPort || 0),
        emailSmtpEnableSsl,
        emailSmtpUser,
        emailSmtpPassword,
        emailSmtpHasPassword,
        emailSmtpFromEmail,
        emailSmtpFromName,
        emailSmtpReplyTo,
        crt,
        cnaeFiscal,
        codigoMunicipioIbge,
        codigoUfIbge: 33,
        ambienteFiscal,
        cscId,
        csc,
        cscHasValue,
        certificadoPfxBase64,
        certificadoSenha,
        certificadoHasValue,
        certificadoThumbprint,
        certificadoValidoAte,
        respTecCnpj,
        respTecContato,
        respTecEmail,
        respTecFone,
      });

      if (data) {
        setFantasyName(data.fantasyName);
        setCorporateName(data.corporateName);
        setCnpj(data.cnpj);
        setStateRegistration(data.stateRegistration);
        setWebsite(data.website);
        setEmail(data.email);
        setTelSac(data.sacPhone);
        setTelefone(data.phone);
        setCelular(data.mobile);
        setCep(data.cep);
        setAddress(data.address);
        setNumber(data.number);
        setNeighborhood(data.neighborhood);
        setCity(data.city);
        setUf(data.uf || "SP");
        setComplement(data.complement);
        setEmailSmtpEnabled(Boolean(data.emailSmtpEnabled));
        setEmailSmtpHost(data.emailSmtpHost || "smtp-mail.outlook.com");
        setEmailSmtpPort(String(data.emailSmtpPort || 587));
        setEmailSmtpEnableSsl(data.emailSmtpEnableSsl ?? true);
        setEmailSmtpUser(data.emailSmtpUser || "");
        setEmailSmtpPassword("");
        setEmailSmtpHasPassword(Boolean(data.emailSmtpHasPassword));
        setEmailSmtpFromEmail(data.emailSmtpFromEmail || data.email || "");
        setEmailSmtpFromName(data.emailSmtpFromName || data.fantasyName || "Hórus PDV");
        setEmailSmtpReplyTo(data.emailSmtpReplyTo || "");
        setCrt(data.crt || 1);
        setCnaeFiscal(data.cnaeFiscal || "");
        setCodigoMunicipioIbge(data.codigoMunicipioIbge || "3304557");
        setAmbienteFiscal(data.ambienteFiscal || 2);
        setCscId(data.cscId || "");
        setCsc("");
        setCscHasValue(Boolean(data.cscHasValue));
        setCertificadoPfxBase64("");
        setCertificadoSenha("");
        setCertificadoFileName("");
        setCertificadoHasValue(Boolean(data.certificadoHasValue));
        setCertificadoThumbprint(data.certificadoThumbprint || "");
        setCertificadoValidoAte(data.certificadoValidoAte);
        setRespTecCnpj(data.respTecCnpj || "");
        setRespTecContato(data.respTecContato || "");
        setRespTecEmail(data.respTecEmail || "");
        setRespTecFone(data.respTecFone || "");
      }
      Toast.success("Dados da empresa salvos com sucesso.");
    } catch (error) {
      Toast.error(error instanceof Error ? error.message : "Erro ao salvar dados da empresa.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <PageLayout className="space-y-4 py-4 md:py-6 lg:py-8">
      <PageHeader
        title="Minha Empresa"
        description="Dados cadastrais e de contato da empresa."
      />

      <section className="card rounded-2xl p-4 md:p-5">
        <form className="grid gap-4 md:grid-cols-12">
          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Nome Fantasia
            </span>
            <input
              className="input-field w-full"
              value={fantasyName}
              onChange={(event) => setFantasyName(event.target.value)}
              placeholder="Nome fantasia"
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Razão Social
            </span>
            <input
              className="input-field w-full"
              value={corporateName}
              onChange={(event) => setCorporateName(event.target.value)}
              placeholder="Razão social"
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              CNPJ
            </span>
            <input
              className="input-field w-full"
              value={cnpj}
              onChange={(event) => setCnpj(maskCnpj(event.target.value))}
              placeholder="00.000.000/0000-00"
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Inscrição Estadual
            </span>
            <input
              className="input-field w-full"
              value={stateRegistration}
              onChange={(event) => setStateRegistration(event.target.value)}
              placeholder="Inscrição estadual"
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Site
            </span>
            <input
              className="input-field w-full"
              value={website}
              onChange={(event) => setWebsite(event.target.value)}
              placeholder="https://www.seusite.com.br"
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Email de Contato
            </span>
            <input
              className="input-field w-full"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="email@empresa.com.br"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Tel SAC
            </span>
            <input
              className="input-field w-full"
              value={telSac}
              onChange={(event) => setTelSac(maskPhoneBr(event.target.value))}
              placeholder="(00) 00000-0000"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Telefone
            </span>
            <input
              className="input-field w-full"
              value={telefone}
              onChange={(event) => setTelefone(maskPhoneBr(event.target.value))}
              placeholder="(00) 00000-0000"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Celular
            </span>
            <input
              className="input-field w-full"
              value={celular}
              onChange={(event) => setCelular(maskPhoneBr(event.target.value))}
              placeholder="(00) 00000-0000"
            />
          </label>

          <label className="block md:col-span-3">
            <span className="mb-1.5 block text-sm text-text-secondary">
              CEP
            </span>
            <input
              className="input-field w-full"
              value={cep}
              onChange={(event) => setCep(maskCep(event.target.value))}
              onBlur={handleCepLookup}
              placeholder="00000-000"
            />
            {cepLookupLoading ? (
              <p className="mt-1 text-xs text-text-secondary">Consultando CEP...</p>
            ) : null}
            {cepLookupError ? (
              <p className="mt-1 text-xs text-primary">{cepLookupError}</p>
            ) : null}
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Endereço
            </span>
            <input
              className="input-field w-full"
              value={address}
              onChange={(event) => setAddress(event.target.value)}
              placeholder="Rua, avenida, alameda..."
            />
          </label>

          <label className="block md:col-span-3">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Número
            </span>
            <input
              className="input-field w-full"
              value={number}
              onChange={(event) => setNumber(event.target.value)}
              placeholder="Número"
            />
          </label>

          <label className="block md:col-span-3">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Bairro
            </span>
            <input
              className="input-field w-full"
              value={neighborhood}
              onChange={(event) => setNeighborhood(event.target.value)}
              placeholder="Bairro"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Cidade
            </span>
            <input
              className="input-field w-full"
              value={city}
              onChange={(event) => setCity(event.target.value)}
              placeholder="Cidade"
            />
          </label>

          <SearchableSelectField
            label="UF"
            value={uf}
            options={UF_SELECT_OPTIONS}
            onChange={(nextValue) => setUf(nextValue)}
            getOptionValue={(option) => option.value}
            getOptionLabel={(option) => option.label}
            placeholder="UF"
            emptyMessage="UF não encontrada."
            className="md:col-span-2"
          />

          <label className="block md:col-span-3">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Complemento
            </span>
            <input
              className="input-field w-full"
              value={complement}
              onChange={(event) => setComplement(event.target.value)}
              placeholder="Apto, bloco..."
            />
          </label>

          <div className="flex justify-end md:col-span-12">
            <LoadingButton
              type="button"
              onClick={saveCompany}
              isLoading={saving}
              loadingLabel="Salvando..."
              className="btn-primary"
            >
              Salvar dados da empresa
            </LoadingButton>
          </div>
        </form>
      </section>

      <section className="card rounded-2xl p-4 md:p-5">
        <div className="mb-4 flex flex-col gap-3 border-b border-border/70 pb-4 md:flex-row md:items-center md:justify-between">
          <div>
            <h2 className="text-base font-semibold text-text-primary">
              Configuração de e-mail
            </h2>
            <p className="mt-1 text-sm text-text-secondary">
              Envio de e-mails do sistema usando a conta da empresa.
            </p>
          </div>

          <YesNoSegmentedControl
            value={emailSmtpEnabled}
            onChange={setEmailSmtpEnabled}
            ariaLabel="Usar envio de e-mails pela conta da empresa"
          />
        </div>

        <form className="grid gap-4 md:grid-cols-12">
          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Host SMTP
            </span>
            <input
              className="input-field w-full"
              value={emailSmtpHost}
              onChange={(event) => setEmailSmtpHost(event.target.value)}
              placeholder="smtp-mail.outlook.com"
              disabled={!emailSmtpEnabled}
            />
          </label>

          <label className="block md:col-span-2">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Porta
            </span>
            <input
              className="input-field w-full"
              inputMode="numeric"
              value={emailSmtpPort}
              onChange={(event) =>
                setEmailSmtpPort(event.target.value.replace(/\D/g, "").slice(0, 5))
              }
              placeholder="587"
              disabled={!emailSmtpEnabled}
            />
          </label>

          <label className="flex items-end md:col-span-4">
            <span className="inline-flex min-h-11 items-center gap-3 text-sm font-medium text-text-primary">
              <input
                type="checkbox"
                checked={emailSmtpEnableSsl}
                onChange={(event) => setEmailSmtpEnableSsl(event.target.checked)}
                disabled={!emailSmtpEnabled}
                className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
              />
              Conexão segura
            </span>
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Usuário SMTP
            </span>
            <input
              className="input-field w-full"
              value={emailSmtpUser}
              onChange={(event) => setEmailSmtpUser(event.target.value)}
              placeholder="email@empresa.com.br"
              disabled={!emailSmtpEnabled}
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Senha de app
            </span>
            <input
              className="input-field w-full"
              type="password"
              value={emailSmtpPassword}
              onChange={(event) => setEmailSmtpPassword(event.target.value)}
              placeholder={
                emailSmtpHasPassword
                  ? "Senha já configurada. Preencha apenas para trocar."
                  : "Senha de app SMTP"
              }
              disabled={!emailSmtpEnabled}
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              E-mail remetente
            </span>
            <input
              className="input-field w-full"
              value={emailSmtpFromEmail}
              onChange={(event) => setEmailSmtpFromEmail(event.target.value)}
              placeholder="naoresponder@empresa.com.br"
              disabled={!emailSmtpEnabled}
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Nome do remetente
            </span>
            <input
              className="input-field w-full"
              value={emailSmtpFromName}
              onChange={(event) => setEmailSmtpFromName(event.target.value)}
              placeholder="Nome da empresa"
              disabled={!emailSmtpEnabled}
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Responder para
            </span>
            <input
              className="input-field w-full"
              value={emailSmtpReplyTo}
              onChange={(event) => setEmailSmtpReplyTo(event.target.value)}
              placeholder="resposta@empresa.com.br"
              disabled={!emailSmtpEnabled}
            />
          </label>

          <div className="flex justify-end md:col-span-12">
            <LoadingButton
              type="button"
              onClick={saveCompany}
              isLoading={saving}
              loadingLabel="Salvando..."
              className="btn-primary"
            >
              Salvar configuração de e-mail
            </LoadingButton>
          </div>
        </form>
      </section>

      <section className="card rounded-2xl p-4 md:p-5">
        <div className="mb-4 border-b border-border/70 pb-4">
          <h2 className="text-base font-semibold text-text-primary">Dados fiscais (NFC-e)</h2>
          <p className="mt-1 text-sm text-text-secondary">
            Emitente usado para assinar e transmitir a NFC-e à SEFAZ-RJ/SVRS. Comece em
            homologação — a razão social do destinatário é substituída automaticamente pela
            frase exigida pela SEFAZ nesse ambiente.
          </p>
        </div>

        <form className="grid gap-4 md:grid-cols-12">
          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">Regime tributário (CRT)</span>
            <select
              className="input-field w-full"
              value={crt}
              onChange={(event) => setCrt(Number(event.target.value))}
            >
              {CRT_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">Ambiente</span>
            <select
              className="input-field w-full"
              value={ambienteFiscal}
              onChange={(event) => setAmbienteFiscal(Number(event.target.value))}
            >
              <option value={2}>Homologação (testes, sem valor fiscal)</option>
              <option value={1}>Produção</option>
            </select>
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">CNAE fiscal</span>
            <input
              className="input-field w-full"
              value={cnaeFiscal}
              onChange={(event) => setCnaeFiscal(onlyDigits(event.target.value).slice(0, 7))}
              placeholder="0000000"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Código do município (IBGE)
            </span>
            <input
              className="input-field w-full"
              value={codigoMunicipioIbge}
              onChange={(event) => setCodigoMunicipioIbge(onlyDigits(event.target.value).slice(0, 7))}
              placeholder="3304557"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">CSC id</span>
            <input
              className="input-field w-full"
              value={cscId}
              onChange={(event) => setCscId(event.target.value.trim())}
              placeholder="000001"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">CSC (token do QR Code)</span>
            <input
              className="input-field w-full"
              type="password"
              value={csc}
              onChange={(event) => setCsc(event.target.value.trim())}
              placeholder={cscHasValue ? "CSC já configurado. Preencha apenas para trocar." : "CSC obtido no portal da SEFAZ-RJ"}
            />
          </label>

          <div className="md:col-span-12">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Certificado digital A1 (.pfx)
            </span>
            <div className="flex flex-col gap-3 rounded-xl border border-dashed border-border-secondary bg-bg-primary/50 p-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="text-sm text-text-secondary">
                {certificadoHasValue ? (
                  <>
                    <p className="font-semibold text-text-primary">Certificado configurado</p>
                    {certificadoThumbprint ? <p>Thumbprint: {certificadoThumbprint}</p> : null}
                    {certificadoValidoAte ? (
                      <p>Válido até: {new Date(certificadoValidoAte).toLocaleDateString("pt-BR")}</p>
                    ) : null}
                  </>
                ) : (
                  <p>Nenhum certificado enviado ainda. Sem certificado, a NFC-e não é emitida.</p>
                )}
                {certificadoFileName ? (
                  <p className="mt-1 text-accent">Novo arquivo selecionado: {certificadoFileName}</p>
                ) : null}
              </div>
              <label className="btn-outline-secondary cursor-pointer text-center">
                Selecionar arquivo .pfx
                <input
                  type="file"
                  accept=".pfx,.p12"
                  className="hidden"
                  onChange={(event) => handleCertificateFile(event.target.files?.[0] ?? null)}
                />
              </label>
            </div>
          </div>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">Senha do certificado</span>
            <input
              className="input-field w-full"
              type="password"
              value={certificadoSenha}
              onChange={(event) => setCertificadoSenha(event.target.value)}
              placeholder="Obrigatória ao enviar novo certificado"
              disabled={!certificadoPfxBase64}
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              CNPJ do responsável técnico
            </span>
            <input
              className="input-field w-full"
              value={respTecCnpj}
              onChange={(event) => setRespTecCnpj(onlyDigits(event.target.value).slice(0, 14))}
              placeholder="Opcional"
            />
          </label>

          <label className="block md:col-span-4">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Contato do responsável técnico
            </span>
            <input
              className="input-field w-full"
              value={respTecContato}
              onChange={(event) => setRespTecContato(event.target.value)}
              placeholder="Opcional"
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              E-mail do responsável técnico
            </span>
            <input
              className="input-field w-full"
              value={respTecEmail}
              onChange={(event) => setRespTecEmail(event.target.value)}
              placeholder="Opcional"
            />
          </label>

          <label className="block md:col-span-6">
            <span className="mb-1.5 block text-sm text-text-secondary">
              Telefone do responsável técnico
            </span>
            <input
              className="input-field w-full"
              value={respTecFone}
              onChange={(event) => setRespTecFone(maskPhoneBr(event.target.value))}
              placeholder="Opcional"
            />
          </label>

          <div className="flex justify-end md:col-span-12">
            <LoadingButton
              type="button"
              onClick={saveCompany}
              isLoading={saving}
              loadingLabel="Salvando..."
              className="btn-primary"
            >
              Salvar dados fiscais
            </LoadingButton>
          </div>
        </form>
      </section>
    </PageLayout>
  );
}
