package br.com.erp.financeiro.gerencial;

import br.com.erp.financeiro.api.ApiException;
import br.com.erp.financeiro.api.Resposta;
import br.com.erp.financeiro.comum.Relogio;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fluxo de caixa e DRE gerencial (§23, §74). */
@Tag(name = "gerencial")
@RestController
public class GerencialController {

  private final FluxoCaixaService fluxo;
  private final DreService dre;
  private final Relogio relogio;

  public GerencialController(FluxoCaixaService fluxo, DreService dre, Relogio relogio) {
    this.fluxo = fluxo;
    this.dre = dre;
    this.relogio = relogio;
  }

  @GetMapping("/api/financeiro/fluxo-caixa")
  @PreAuthorize("hasAuthority('financeiro.fluxocaixa.ver')")
  public Resposta<FluxoCaixaService.Fluxo> fluxo(
      @RequestParam(required = false) LocalDate de,
      @RequestParam(required = false) LocalDate ate,
      @RequestParam(required = false) FluxoCaixaService.Agrupamento agrupamento,
      @RequestParam(required = false) UUID contaBancariaId) {
    LocalDate inicio = de != null ? de : relogio.hoje().withDayOfMonth(1);
    LocalDate fim = ate != null ? ate : inicio.plusMonths(3).minusDays(1);
    return Resposta.ok(fluxo.calcular(inicio, fim, agrupamento == null ? FluxoCaixaService.Agrupamento.MES : agrupamento, contaBancariaId));
  }

  /** de/ate no formato AAAA-MM (ou data, da qual se usa o mês). */
  @GetMapping("/api/financeiro/dre")
  @PreAuthorize("hasAuthority('financeiro.dre.ver')")
  public Resposta<DreService.Dre> dre(
      @RequestParam(required = false) String de,
      @RequestParam(required = false) String ate,
      @RequestParam(required = false) DreService.Regime regime) {
    YearMonth fim = mes(ate, "ate", relogio.mesAtual());
    YearMonth inicio = mes(de, "de", fim.minusMonths(5));
    return Resposta.ok(dre.calcular(inicio, fim, regime == null ? DreService.Regime.COMPETENCIA : regime));
  }

  static YearMonth mes(String s, String campo, YearMonth padrao) {
    if (s == null || s.isBlank()) return padrao;
    try {
      return YearMonth.parse(s.length() > 7 ? s.substring(0, 7) : s);
    } catch (DateTimeParseException e) {
      throw ApiException.campo(campo, "FORMATO_INVALIDO", "Use AAAA-MM.");
    }
  }
}
