/**
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.pricer.rate.deposit;

import static com.opengamma.strata.basics.currency.Currency.EUR;
import static com.opengamma.strata.basics.date.BusinessDayConventions.MODIFIED_FOLLOWING;
import static com.opengamma.strata.basics.date.DayCounts.ACT_ACT_ISDA;
import static com.opengamma.strata.basics.date.HolidayCalendars.EUTA;
import static com.opengamma.strata.basics.index.IborIndices.EUR_EURIBOR_6M;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.time.LocalDate;

import org.testng.annotations.Test;

import com.google.common.collect.ImmutableMap;
import com.opengamma.analytics.financial.model.interestrate.curve.YieldCurve;
import com.opengamma.analytics.math.curve.InterpolatedDoublesCurve;
import com.opengamma.analytics.math.interpolation.CombinedInterpolatorExtrapolator;
import com.opengamma.analytics.math.interpolation.CombinedInterpolatorExtrapolatorFactory;
import com.opengamma.analytics.math.interpolation.Interpolator1DFactory;
import com.opengamma.strata.basics.BuySell;
import com.opengamma.strata.basics.currency.CurrencyAmount;
import com.opengamma.strata.basics.date.BusinessDayAdjustment;
import com.opengamma.strata.collect.timeseries.LocalDateDoubleTimeSeries;
import com.opengamma.strata.finance.rate.deposit.ExpandedIborFixingDeposit;
import com.opengamma.strata.finance.rate.deposit.IborFixingDeposit;
import com.opengamma.strata.market.sensitivity.CurveParameterSensitivity;
import com.opengamma.strata.market.sensitivity.PointSensitivities;
import com.opengamma.strata.pricer.impl.rate.ForwardIborRateObservationFn;
import com.opengamma.strata.pricer.rate.ImmutableRatesProvider;
import com.opengamma.strata.pricer.rate.RatesProvider;
import com.opengamma.strata.pricer.sensitivity.RatesFiniteDifferenceSensitivityCalculator;

/**
 * Test {@link DiscountingIborFixingDepositProductPricer}.
 */
@Test
public class DiscountingIborFixingDepositProductPricerTest {
  private static final LocalDate VALUATION_DATE = LocalDate.of(2014, 1, 16);
  private static final LocalDate START_DATE = LocalDate.of(2014, 1, 24);
  private static final LocalDate END_DATE = LocalDate.of(2014, 7, 24);
  private static final double NOTIONAL = 100000000d;
  private static final double RATE = 0.0150;
  private static final BusinessDayAdjustment BD_ADJ = BusinessDayAdjustment.of(MODIFIED_FOLLOWING, EUTA);
  private static final IborFixingDeposit DEPOSIT = IborFixingDeposit.builder()
      .businessDayAdjustment(BD_ADJ)
      .buySell(BuySell.BUY)
      .startDate(START_DATE)
      .endDate(END_DATE)
      .index(EUR_EURIBOR_6M)
      .notional(NOTIONAL)
      .rate(RATE)
      .build();
  private static final double TOLERANCE = 1E-13;

  private static final double EPS_FD = 1E-7;
  private static final RatesFiniteDifferenceSensitivityCalculator CAL_FD =
      new RatesFiniteDifferenceSensitivityCalculator(EPS_FD);
  private static final ImmutableRatesProvider IMM_PROV;
  static {
    CombinedInterpolatorExtrapolator interp = CombinedInterpolatorExtrapolatorFactory.getInterpolator(
        Interpolator1DFactory.DOUBLE_QUADRATIC,
        Interpolator1DFactory.FLAT_EXTRAPOLATOR,
        Interpolator1DFactory.FLAT_EXTRAPOLATOR);
    double[] time_eur = new double[] {0.0, 0.1, 0.25, 0.5, 0.75, 1.0, 2.0 };
    double[] rate_eur = new double[] {0.0160, 0.0165, 0.0155, 0.0155, 0.0155, 0.0150, 0.014 };
    InterpolatedDoublesCurve curve_eur = InterpolatedDoublesCurve.from(time_eur, rate_eur, interp);
    YieldCurve dscCurve = new YieldCurve("EUR-Discount", curve_eur);
    double[] time_index = new double[] {0.0, 0.25, 0.5, 1.0 };
    double[] rate_index = new double[] {0.0180, 0.0180, 0.0175, 0.0165 };
    InterpolatedDoublesCurve curve_index = InterpolatedDoublesCurve.from(time_index, rate_index, interp);
    YieldCurve indexCurve = new YieldCurve("EUR-EURIBOR6M", curve_index);
    IMM_PROV = ImmutableRatesProvider.builder()
        .valuationDate(VALUATION_DATE)
        .discountCurves(ImmutableMap.of(EUR, dscCurve))
        .indexCurves(ImmutableMap.of(EUR_EURIBOR_6M, indexCurve))
        .dayCount(ACT_ACT_ISDA)
        .timeSeries(ImmutableMap.of(EUR_EURIBOR_6M, LocalDateDoubleTimeSeries.empty()))
        .build();
  }

  public void test_presentValue() {
    ExpandedIborFixingDeposit deposit = DEPOSIT.expand();
    ForwardIborRateObservationFn mockObs = mock(ForwardIborRateObservationFn.class);
    DiscountingIborFixingDepositProductPricer test = new DiscountingIborFixingDepositProductPricer(mockObs);
    RatesProvider mockProv = mock(RatesProvider.class);
    when(mockProv.getValuationDate()).thenReturn(VALUATION_DATE);
    double discountFactor = 0.95;
    double forwardRate = 0.02;
    when(mockProv.discountFactor(EUR, END_DATE)).thenReturn(discountFactor);
    when(mockObs.rate(deposit.getFloatingRate(), deposit.getStartDate(), deposit.getEndDate(), mockProv))
        .thenReturn(forwardRate);
    CurrencyAmount computed = test.presentValue(DEPOSIT, mockProv);
    double expected = NOTIONAL * discountFactor * (RATE - forwardRate) * deposit.getYearFraction();
    assertEquals(computed.getCurrency(), EUR);
    assertEquals(computed.getAmount(), expected, NOTIONAL * TOLERANCE);
  }

  public void test_presentValue_ended() {
    ExpandedIborFixingDeposit deposit = DEPOSIT.expand();
    ForwardIborRateObservationFn mockObs = mock(ForwardIborRateObservationFn.class);
    DiscountingIborFixingDepositProductPricer test = new DiscountingIborFixingDepositProductPricer(mockObs);
    RatesProvider mockProv = mock(RatesProvider.class);
    when(mockProv.getValuationDate()).thenReturn(LocalDate.of(2014, 8, 24));
    double discountFactor = 0.95;
    double forwardRate = 0.02;
    when(mockProv.discountFactor(EUR, END_DATE)).thenReturn(discountFactor);
    when(mockObs.rate(deposit.getFloatingRate(), deposit.getStartDate(), deposit.getEndDate(), mockProv))
        .thenReturn(forwardRate);
    CurrencyAmount computed = test.presentValue(DEPOSIT, mockProv);
    assertEquals(computed.getCurrency(), EUR);
    assertEquals(computed.getAmount(), 0.0d, NOTIONAL * TOLERANCE);
  }

  public void test_presentValueSensitivity() {
    DiscountingIborFixingDepositProductPricer test = DiscountingIborFixingDepositProductPricer.DEFAULT;
    PointSensitivities computed = test.presentValueSensitivity(DEPOSIT, IMM_PROV);
    CurveParameterSensitivity sensiComputed = IMM_PROV.parameterSensitivity(computed);
    CurveParameterSensitivity sensiExpected = CAL_FD.sensitivity(IMM_PROV, (p) -> test.presentValue(DEPOSIT, (p)));
    assertTrue(sensiComputed.equalWithTolerance(sensiExpected, NOTIONAL * EPS_FD));
  }

  public void test_parRate() {
    ExpandedIborFixingDeposit deposit = DEPOSIT.expand();
    ForwardIborRateObservationFn mockObs = mock(ForwardIborRateObservationFn.class);
    DiscountingIborFixingDepositProductPricer test = new DiscountingIborFixingDepositProductPricer(mockObs);
    RatesProvider mockProv = mock(RatesProvider.class);
    when(mockProv.getValuationDate()).thenReturn(VALUATION_DATE);
    double discountFactor = 0.95;
    double forwardRate = 0.02;
    when(mockProv.discountFactor(EUR, END_DATE)).thenReturn(discountFactor);
    when(mockObs.rate(deposit.getFloatingRate(), deposit.getStartDate(), deposit.getEndDate(), mockProv))
        .thenReturn(forwardRate);
    double parRate = test.parRate(DEPOSIT, mockProv);
    assertEquals(parRate, forwardRate, TOLERANCE);
    IborFixingDeposit depositPar = IborFixingDeposit.builder()
        .businessDayAdjustment(BD_ADJ)
        .buySell(BuySell.BUY)
        .startDate(START_DATE)
        .endDate(END_DATE)
        .index(EUR_EURIBOR_6M)
        .notional(NOTIONAL)
        .rate(parRate)
        .build();
    CurrencyAmount computedPar = test.presentValue(depositPar, mockProv);
    assertEquals(computedPar.getAmount(), 0.0, NOTIONAL * TOLERANCE);
  }

  public void test_parSpread() {
    ExpandedIborFixingDeposit deposit = DEPOSIT.expand();
    ForwardIborRateObservationFn mockObs = mock(ForwardIborRateObservationFn.class);
    DiscountingIborFixingDepositProductPricer test = new DiscountingIborFixingDepositProductPricer(mockObs);
    RatesProvider mockProv = mock(RatesProvider.class);
    when(mockProv.getValuationDate()).thenReturn(VALUATION_DATE);
    double discountFactor = 0.95;
    double forwardRate = 0.02;
    when(mockProv.discountFactor(EUR, END_DATE)).thenReturn(discountFactor);
    when(mockObs.rate(deposit.getFloatingRate(), deposit.getStartDate(), deposit.getEndDate(), mockProv))
        .thenReturn(forwardRate);
    double parRate = test.parSpread(DEPOSIT, mockProv);
    IborFixingDeposit depositPar = IborFixingDeposit.builder()
        .businessDayAdjustment(BD_ADJ)
        .buySell(BuySell.BUY)
        .startDate(START_DATE)
        .endDate(END_DATE)
        .index(EUR_EURIBOR_6M)
        .notional(NOTIONAL)
        .rate(RATE + parRate)
        .build();
    CurrencyAmount computedPar = test.presentValue(depositPar, mockProv);
    assertEquals(computedPar.getAmount(), 0.0, NOTIONAL * TOLERANCE);
  }

  public void test_parSpreadSensitivity() {
    DiscountingIborFixingDepositProductPricer test = DiscountingIborFixingDepositProductPricer.DEFAULT;
    PointSensitivities computed = test.parSpreadSensitivity(DEPOSIT, IMM_PROV);
    CurveParameterSensitivity sensiComputed = IMM_PROV.parameterSensitivity(computed);
    CurveParameterSensitivity sensiExpected =
        CAL_FD.sensitivity(IMM_PROV, (p) -> CurrencyAmount.of(EUR, test.parSpread(DEPOSIT, (p))));
    assertTrue(sensiComputed.equalWithTolerance(sensiExpected, NOTIONAL * EPS_FD));
  }
}
