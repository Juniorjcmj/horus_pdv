package br.com.quack.pdv.cadastro.categoria.web;
import java.util.*;
public class CategoriaDto {
    public String id;public String nome;public String categoriaPaiId;public String categoriaPaiNome;public int ordem;public boolean ativa;public int quantidadeProdutos;public List<CategoriaDto> subcategorias=new ArrayList<>();
}
