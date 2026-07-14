update stock_assets
set instrument_type = 'INDEX'
where upper(ticker_symbol) in (
    'SPX', 'GSPC', 'DJI', 'DJIA', 'IXIC', 'NDX', 'RUT', 'VIX',
    'FTSE', 'GDAXI', 'FCHI', 'N225', 'HSI', 'STOXX50E'
);
