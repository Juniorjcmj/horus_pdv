import { onlyDigits } from "./inputMasks";
import { getAgeFromBirthDate, isValidCnpj, isValidCpf, isValidEmail } from "./validators";

type CustomerInput = {
  customerName: string;
  telephone?: string;
  cellphone?: string;
  document?: string;
  email?: string;
  birthDate?: string;
};

/** Nome e um telefone com DDD são obrigatórios; os demais dados são validados se preenchidos. */
export function getCustomerValidationError(customer: CustomerInput): string | null {
  if (!customer.customerName.trim()) return "Informe o nome do cliente.";
  if (customer.customerName.trim().length < 3) return "O nome do cliente deve ter no mínimo 3 caracteres.";

  const phones = [customer.telephone ?? "", customer.cellphone ?? ""].filter(value => value.trim());
  if (!phones.length) return "Informe um telefone ou celular com DDD.";
  if (phones.some(value => ![10, 11].includes(onlyDigits(value).length))) {
    return "Informe um telefone ou celular válido com DDD (10 ou 11 dígitos).";
  }

  if (customer.document?.trim() && !isValidCpf(customer.document) && !isValidCnpj(customer.document)) {
    return "Documento inválido.";
  }
  if (customer.birthDate?.trim() && getAgeFromBirthDate(customer.birthDate) === null) {
    return "Data de nascimento inválida.";
  }
  if (!isValidEmail(customer.email ?? "")) return "E-mail inválido.";
  return null;
}
