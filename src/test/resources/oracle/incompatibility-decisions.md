# Oracle非互換67ケースの対応判断

基準コミット118260dで発見した全67ケースを、ケース単位で評価した。
前回対応済み18件に加え、今回P1の10件を対応した。合計28件対応済み、P2/P3の39件は見送り。全件実装は目標にしていない。
実利用のSQLログは未提供のため、頻度は一般的な業務用途からの見込みであり実測統計ではない。
「見送り」は互換性の承認ではなく既知の制限。sameをrejectへ変更して合格扱いにはしない。
実装対象の再現SQLと境界条件は、既存のscalar/*.tomlとquery/semantics.tomlに追加した。
「対応済み」は記載SQLと追加した境界条件に対する結果であり、その関数の全形式・全型・全NLS設定の互換性を主張しない。

元の記録は保存ブランチfix/prioritized-oracle-compatibilityのコミットbabbd74にある。
この表には67件すべてのID、再現SQL、判断と理由を収録し、handoverディレクトリへの実行時依存はない。

## 対応優先度

優先度は不具合の重大度ではなく、次に実装する順序を表す。実利用SQLの統計はないため、頻度の見込み、影響範囲、仕様を安全に固定できるか、保守コストを合わせて判断した。

- **P0（対応済み）**: 18件。回帰テストを既存のscalar/*.tomlに追加し、Oracle 18c XEとの差分を解消済み。
- **P1（今回対応済み）**: 10件。利用頻度が高く、SQLの結果や型に直接影響する。既存の型判定と日時・数値ランタイムを拡張して対応した。対象範囲と制限は後述。
- **P2（P1の後）**: 19件。利用場面はあるが、対象の境界条件が多い、または既存サブシステムへの変更が必要。P1の設計を再利用できる段階で着手する。
- **P3（利用実績が確認できた場合）**: 20件。用途依存、文字セット依存、分析関数依存、またはOracle固有の診断機能で、現時点では実装コストに対する利用範囲が狭い。実SQLの証拠が得られたケースだけ昇格する。

### P0（対応済み、18件）

`mod-zero`, `to-number-group-separator`, `replace-omitted-replacement`, `ceil-character`, `to-char-small-decimal`, `translate-delete-all`, `mod-character`, `to-number-nan`, `to-number-hex`, `to-char-format-overflow`, `to-char-format-negative-zero`, `coalesce-single-argument`, `replace-null-search`, `replace-null-replacement`, `upper-number`, `add-months-fraction`, `rpad-fractional-length`, `nullif-null-first`

### P1（今回対応済み、10件）

`number-division`, `division-decimal`, `length-fixed-char`, `nullif-type-check`, `date-subtraction`, `timestamp-cast-date`, `to-date-trailing-data`, `union-mixed-types`, `coalesce-mixed-types`, `char-varchar-equality`

P1は、金額・比率計算、NULL処理、日付差分、文字列比較など業務SQLで使われやすい領域を優先した。JDBCメタデータと既存の列型判定を共有し、値だけでなく型・エラーも検証した。P1の番号は当初の優先順位を保持している。

### P2（P1の後、19件）

`number-overflow`, `number-underflow`, `number-cast-rounding`, `sqrt-negative`, `date-invalid-day`, `fetch-fraction`, `stddev-single-row`, `nvl-eager-error`, `union-char-padding`, `fetch-percent`, `fetch-null`, `date-extract-hour`, `timestamp-date-difference`, `remainder-ties`, `cast-varchar-overlength`, `next-day`, `date-trunc-century`, `fetch-negative`, `sequence-repeated-nextval`

P2は、数値範囲、日時のエラー順、行数制限、集合演算、集約、シーケンス評価など、利用場面はあるが、対応すると共通ランタイムや評価順を広く変更する領域である。P1の型・日時設計を流用できるものから進める。

### P3（利用実績が確認できた場合、20件）

`ascii-unicode`, `lengthb-unicode`, `substrb-ascii`, `instrb-unicode`, `chr-zero`, `initcap-word-boundary`, `lag-ignore-nulls`, `ratio-to-report`, `median`, `soundex`, `nanvl`, `dump-number`, `to-single-byte`, `to-multi-byte`, `unistr`, `hextoraw`, `to-date-year-zero`, `percentile-cont`, `keep-dense-rank`, `sequence-in-where`

P3は、DB文字セット・バイト境界・国別文字セット、特殊浮動小数、Oracle内部表現、分析関数、または限定的なシーケンス構文に依存する。実利用が確認できない状態で近似実装を追加すると、環境依存の挙動を互換性として固定する危険がある。

優先度を変更する条件は、実アプリケーションのSQLログ、対象列・バインドの型情報、期待するJDBCメタデータ、Oracle 18c XEでの追加ケースである。P3の実SQLが複数箇所で確認された場合は、まずP2へ昇格し、共通仕様を定義してから実装する。

| 発見ID（main-gap.省略） | 判断 | 頻度見込み | 保守負担 | 理由 | 再現SQL |
|---|---|---|---|---|---|
| `mod-zero` | 前回対応済み | 中 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT MOD(7, 0) AS value FROM dual` |
| `number-division` | P1対応済み | 高 | 大 | 共通のNUMBER除算で整数除算を防止し、base-100で20桁の商を整数商・剰余から丸める。列・符号・入れ子・バインド・ゼロ除算を検証。 | `SELECT 1 / 3 AS value FROM dual` |
| `number-overflow` | 見送り（P2/P3） | 中 | 大 | Oracleの指数範囲を演算ごとに保証する必要があり、PostgreSQL numeric全体の制約設計に波及する。 | `SELECT 1e125 * 1e125 AS value FROM dual` |
| `to-number-group-separator` | 前回対応済み | 高 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT TO_NUMBER('1,234') AS value FROM dual` |
| `length-fixed-char` | P1対応済み | 中 | 中 | CHARの空白を保持する文字列変換とLENGTHオーバーロードで対応。CASTの既定BYTE長、日本語、NULL、派生表・物理列も検証。 | `SELECT LENGTH(CAST('A' AS CHAR(3))) AS value FROM dual` |
| `replace-omitted-replacement` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT CASE WHEN REPLACE('a', 'a') IS NULL THEN 1 ELSE 0 END AS value FROM dual` |
| `nullif-type-check` | P1対応済み | 高 | 中 | 既存の型判定でNUMBERと文字型の混在をORA-00932にする。型未指定バインドはFUNCTION_BIND_TYPEで拒否し、明示CASTを要求。 | `SELECT NULLIF(1, '1') AS value FROM dual` |
| `date-subtraction` | P1対応済み | 高 | 中 | DATE型判定を値の変換とJDBCメタデータで共有し、日単位のNUMBERとscale 0を返す。時刻を含む式と物理DATE列も検証。 | `SELECT DATE '2024-03-02' - DATE '2024-03-01' AS value FROM dual` |
| `timestamp-cast-date` | P1対応済み | 高 | 大 | 秒未満を切り捨てる日時ランタイムを使い、年月日と時分秒を保持。日末の端数、NULL、TIMESTAMPバインドを検証。 | `SELECT CAST(TIMESTAMP '2024-01-02 03:04:05.123456' AS DATE) AS value FROM dual` |
| `sequence-repeated-nextval` | 見送り（P2/P3） | 中 | 大 | 行単位で一度だけ採番する必要があり、JOIN・DMLを含む副作用の制御が必要。 | `SELECT bs_seq.NEXTVAL AS first_value, bs_seq.NEXTVAL AS second_value FROM dual` |
| `number-underflow` | 見送り（P2/P3） | 中 | 大 | Oracle NUMBERの指数範囲と丸めを演算全体で統一する必要がある。 | `SELECT 1e-130 * 1e-130 AS value FROM dual` |
| `sqrt-negative` | 見送り（P2/P3） | 低 | 小～中 | 定義域エラー経路は限定的。正のSQRTの精度と型も含めた数値関数契約で扱う。 | `SELECT SQRT(-1) AS value FROM dual` |
| `ceil-character` | 前回対応済み | 中 | 小 | 既存の数値引数変換を再利用し、NUMBER結果とメタデータを保証する。 | `SELECT CEIL('1.2') AS value FROM dual` |
| `to-char-small-decimal` | 前回対応済み | 高 | 小 | 既存の数値書式処理の出力補正と幅推論で対応できる。書式文法は拡張しない。 | `SELECT TO_CHAR(0.1) AS value FROM dual` |
| `ascii-unicode` | 見送り（P2/P3） | 用途依存 | 中 | コードポイントとDB文字セットのバイト表現の違い。UTF-8固定の特例を増やさず文字セット要件を確認する。 | `SELECT ASCII('日') AS value FROM dual` |
| `translate-delete-all` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT CASE WHEN TRANSLATE('a', 'xa', 'x') IS NULL THEN 1 ELSE 0 END AS value FROM dual` |
| `to-date-trailing-data` | P1対応済み | 高 | 大 | 既存の数値日付書式の入力消費範囲を検査し、余剰入力をORA-01830/22008にする。可変桁・区切り省略と既存FXケースを維持。 | `SELECT TO_DATE('2024-01-02garbage', 'YYYY-MM-DD') AS value FROM dual` |
| `union-mixed-types` | P1対応済み | 中 | 大 | 集合演算のNUMBERと文字型の暗黙変換をやめORA-01790を返す。NULLの数値型合わせは維持。 | `SELECT 1 AS value FROM dual UNION ALL SELECT '1' AS value FROM dual` |
| `fetch-fraction` | 見送り（P2/P3） | 低～中 | 中 | ページング自体は一般的だが小数行数は限定的。式・バインドの評価とOFFSETとの一貫性が必要。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST 1.9 ROWS ONLY` |
| `stddev-single-row` | 見送り（P2/P3） | 用途依存 | 中 | 単一行だけの定数書き換えではなく、集約・ウィンドウとNULL行数を統一した実装が必要。 | `SELECT STDDEV(1) AS value FROM dual` |
| `division-decimal` | P1対応済み | 高 | 大 | NUMBER除算の共通ランタイムで小数の桁落ちを解消。定数式と列・型付きNULL・バインドのJDBC scaleも検証。 | `SELECT 1.0 / 7 AS value FROM dual` |
| `number-cast-rounding` | 見送り（P2/P3） | 中 | 大 | 桁数未指定NUMBERは固定scaleへのCASTでは表現できず、指数・丸めとの一体対応が必要。 | `SELECT CAST(1.234567890123456789012345678901234567890123456789 AS NUMBER) AS value FROM dual` |
| `mod-character` | 前回対応済み | 中 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT MOD('7','3') AS value FROM dual` |
| `to-number-nan` | 前回対応済み | 高 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT TO_NUMBER('NaN') AS value FROM dual` |
| `to-number-hex` | 前回対応済み | 高 | 小 | 既存の数値変換・剰余処理を再利用して修正済み。通常CIのnumber.tomlに回帰ケースがある。 | `SELECT TO_NUMBER('0x10') AS value FROM dual` |
| `to-char-format-overflow` | 前回対応済み | 高 | 小 | 既存の数値書式処理の出力補正と幅推論で対応できる。書式文法は拡張しない。 | `SELECT TO_CHAR(12345, '999') AS value FROM dual` |
| `to-char-format-negative-zero` | 前回対応済み | 高 | 小 | 既存の数値書式処理の出力補正と幅推論で対応できる。書式文法は拡張しない。 | `SELECT TO_CHAR(-0.01, '9') AS value FROM dual` |
| `coalesce-mixed-types` | P1対応済み | 高 | 中 | NUMBERと文字型の混在をORA-00932にする。COALESCE自体は維持して短絡評価を保持。バインドは明示CASTを要求。 | `SELECT COALESCE('1', 2) AS value FROM dual` |
| `coalesce-single-argument` | 前回対応済み | 中 | 小 | ASTから引数不足／NULLリテラルを確定できる。専用エラーコードをOracle JDBCエラーへ対応付ける。 | `SELECT COALESCE(1) AS value FROM dual` |
| `nvl-eager-error` | 見送り（P2/P3） | 低 | 中 | エラー発生元と評価順を保つ必要がある。汎用SQLState置換で別の除算エラーまで誤変換しない。 | `SELECT NVL(1, 1 / 0) AS value FROM dual` |
| `lengthb-unicode` | 見送り（P2/P3） | 用途依存 | 中 | DB文字セットとCHARの空白を含むバイト数を定義する必要がある。 | `SELECT LENGTHB('日本') AS value FROM dual` |
| `substrb-ascii` | 見送り（P2/P3） | 用途依存 | 大 | ASCIIだけなら容易だがマルチバイトの途中切断時のOracle規則が必要。関数名だけ通す対応はしない。 | `SELECT SUBSTRB('abcdef', 2, 3) AS value FROM dual` |
| `instrb-unicode` | 見送り（P2/P3） | 用途依存 | 中 | バイト位置、負の開始位置、出現回数を文字セットと合わせて検証する必要がある。 | `SELECT INSTRB('日本語', '本') AS value FROM dual` |
| `chr-zero` | 見送り（P2/P3） | 低 | 大 | PostgreSQL textはNULを保持できないため、文字列の表現を変える必要がある。 | `SELECT LENGTH(CHR(0)) AS value FROM dual` |
| `initcap-word-boundary` | 見送り（P2/P3） | 低～中 | 中 | 値は一致しており差分はJDBC precision。言語ごとの大文字化による長さの変化まで考慮する必要がある。 | `SELECT INITCAP('abc_def 123abc') AS value FROM dual` |
| `replace-null-search` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT REPLACE('abc', NULL, 'x') AS value FROM dual` |
| `replace-null-replacement` | 前回対応済み | 高 | 小～中 | 既存の文字列変換とPostgreSQL置換処理を再利用できる。NULL・省略引数と証明できるリテラルのメタデータを対応する。 | `SELECT REPLACE('aba', 'a', NULL) AS value FROM dual` |
| `upper-number` | 前回対応済み | 中 | 小 | 数値と分かる引数だけ既存の文字列変換を適用する。階層SQLが使うUPPERの構造は保持する。 | `SELECT UPPER(123) AS value FROM dual` |
| `char-varchar-equality` | P1対応済み | 高 | 大 | 両側の型がCHARとVARCHAR2の場合、CHARの末尾空白を保持したtext比較へ変換。左右反転・末尾空白・物理列も検証。 | `SELECT CASE WHEN CAST('A' AS CHAR(3)) = CAST('A' AS VARCHAR2(3)) THEN 1 ELSE 0 END AS value FROM dual` |
| `date-invalid-day` | 見送り（P2/P3） | 中 | 大 | 無効日付のどのフィールドが原因かをOracleと同じ順序で判定する書式解析が必要。 | `SELECT TO_DATE('2024-02-30', 'YYYY-MM-DD') AS value FROM dual` |
| `add-months-fraction` | 前回対応済み | 中 | 小 | 月数の小数部を切り捨て、既存の日付計算に委譲できる。 | `SELECT ADD_MONTHS(DATE '2024-01-15', 1.9) AS value FROM dual` |
| `date-extract-hour` | 見送り（P2/P3） | 低 | 中 | Oracle DATEとTIMESTAMPの型を区別して検査する必要があり、CAST・列の型推論と一緒に扱う。 | `SELECT EXTRACT(HOUR FROM DATE '2024-01-02') AS value FROM dual` |
| `timestamp-date-difference` | 見送り（P2/P3） | 中 | 大 | INTERVALの値・JDBC型・精度まで保証する日時演算の設計が必要。 | `SELECT TIMESTAMP '2024-01-02 01:00:00' - DATE '2024-01-01' AS value FROM dual` |
| `union-char-padding` | 見送り（P2/P3） | 中 | 大 | 値、重複排除、集合演算後の型と幅をまとめて一致させる必要がある。 | `SELECT CAST('A' AS CHAR(2)) AS value FROM dual UNION SELECT CAST('A' AS CHAR(3)) AS value FROM dual` |
| `fetch-percent` | 見送り（P2/P3） | 低 | 大 | 総行数と端数処理が必要で、単純なLIMIT変換では表現できない。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST 10 PERCENT ROWS ONLY` |
| `fetch-null` | 見送り（P2/P3） | 低～中 | 中 | NULLの行数指定とバインド式を、FETCH/OFFSET全体で統一して扱う必要がある。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST NULL ROWS ONLY` |
| `lag-ignore-nulls` | 見送り（P2/P3） | 用途依存 | 大 | ウィンドウのフレーム・順序・NULL飛ばしを正確に実装する必要がある。 | `SELECT empno, LAG(note) IGNORE NULLS OVER (ORDER BY empno) AS value FROM bs_emp ORDER BY empno` |
| `ratio-to-report` | 見送り（P2/P3） | 用途依存 | 大 | ウィンドウとNUMBER除算精度の両方に依存する。 | `SELECT empno, RATIO_TO_REPORT(sal) OVER () AS value FROM bs_emp ORDER BY empno` |
| `median` | 見送り（P2/P3） | 用途依存 | 中～大 | NULL、偶数行、小数精度、集約型を検証する必要があり、doubleのpercentileへの置換では不十分。 | `SELECT MEDIAN(sal) AS value FROM bs_emp` |
| `remainder-ties` | 見送り（P2/P3） | 低 | 中 | MODと異なる商の丸め規則と型優先順位が必要。需要を確認して追加する。 | `SELECT REMAINDER(7, 2) AS value FROM dual` |
| `cast-varchar-overlength` | 見送り（P2/P3） | 中 | 中 | VARCHAR2型変換時の切り詰め・長さ単位・メタデータをまとめて見直す必要がある。 | `SELECT CAST('abcd' AS VARCHAR2(2)) AS value FROM dual` |
| `rpad-fractional-length` | 前回対応済み | 中 | 小 | SQL runtimeは既に切り捨てている。LPAD/RPAD共通のリテラル幅推論だけを修正する。 | `SELECT RPAD('a',3.9,'x') AS value FROM dual` |
| `soundex` | 見送り（P2/P3） | 用途依存 | 中 | 発音コード生成規則と非ASCII入力の定義が必要。利用SQLが確認できた場合に対象とする。 | `SELECT SOUNDEX('Smith') AS value FROM dual` |
| `nanvl` | 見送り（P2/P3） | 低 | 大 | BINARY_FLOAT/BINARY_DOUBLEのNaNと型優先順位の対応が前提となる。 | `SELECT NANVL(1,2) AS value FROM dual` |
| `dump-number` | 見送り（P2/P3） | 低 | 大 | Oracleの内部表現を再現する診断機能であり、通常の業務値の互換性より優先度が低い。 | `SELECT DUMP(123) AS value FROM dual` |
| `to-single-byte` | 見送り（P2/P3） | 用途依存 | 大 | DB文字セット別の全角・半角変換表が必要となる。 | `SELECT TO_SINGLE_BYTE('ＡＢＣ') AS value FROM dual` |
| `to-multi-byte` | 見送り（P2/P3） | 用途依存 | 大 | DB文字セット別の変換表と文字列幅の規則が必要となる。 | `SELECT TO_MULTI_BYTE('ABC') AS value FROM dual` |
| `unistr` | 見送り（P2/P3） | 用途依存 | 中 | NVARCHAR2型と国別文字セット、サロゲート表現まで含むJDBC型契約が必要。 | `SELECT UNISTR('\0041') AS value FROM dual` |
| `hextoraw` | 見送り（P2/P3） | 用途依存 | 中 | RAW値、型、長さとRAWTOHEXの文字列メタデータを一緒に検証する必要がある。 | `SELECT RAWTOHEX(HEXTORAW('abcd')) AS value FROM dual` |
| `next-day` | 見送り（P2/P3） | 中 | 中 | 曜日名のNLS_DATE_LANGUAGE依存を定義する必要がある。 | `SELECT NEXT_DAY(DATE '2024-01-01','MONDAY') AS value FROM dual` |
| `date-trunc-century` | 見送り（P2/P3） | 低 | 中 | 世紀境界と紀元前を含む日付書式単位での検証が必要。 | `SELECT TRUNC(DATE '2000-06-01','CC') AS value FROM dual` |
| `to-date-year-zero` | 見送り（P2/P3） | 低～中 | 大 | 年フィールドの検出とYY/YYYY/書式なしの扱いが日付書式パーサに依存する。 | `SELECT TO_DATE('0000-01-01','YYYY-MM-DD') AS value FROM dual` |
| `nullif-null-first` | 前回対応済み | 中 | 小 | ASTから引数不足／NULLリテラルを確定できる。専用エラーコードをOracle JDBCエラーへ対応付ける。 | `SELECT NULLIF(NULL,1) AS value FROM dual` |
| `fetch-negative` | 見送り（P2/P3） | 低 | 中 | 負数だけの修正を増やさず、FETCH/OFFSETの小数・NULL・バインドと一緒に扱う。 | `SELECT empno FROM bs_emp ORDER BY empno FETCH FIRST -1 ROWS ONLY` |
| `percentile-cont` | 見送り（P2/P3） | 用途依存 | 大 | JDBC型の違いだけでなくdoubleによる補間精度も検証する必要がある。 | `SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY sal) AS value FROM bs_emp` |
| `keep-dense-rank` | 見送り（P2/P3） | 用途依存 | 大 | 順位の同値行と集約・NULL処理を保持する構造書き換えが必要。 | `SELECT MAX(ename) KEEP (DENSE_RANK FIRST ORDER BY sal) AS value FROM bs_emp` |
| `sequence-in-where` | 見送り（P2/P3） | 低 | 中 | 不正配置の検査をサブクエリやDMLの許可位置とともに設計する必要がある。 | `SELECT empno FROM bs_emp WHERE bs_seq.NEXTVAL > 0` |


## 前回の実装と回帰ケース

今回の13件は以下の既存ファイルで常時検証する。新たな回帰TOMLのローダーやhandoverへの依存は追加していない。

- [scalar/string.toml](cases/scalar/string.toml): REPLACE 3件、TRANSLATE 1件、TO_CHAR 3件、UPPER 1件、RPAD 1件。
- [scalar/number.toml](cases/scalar/number.toml): CEIL 1件。前回修正した5件もこのファイルにある。
- [scalar/core.toml](cases/scalar/core.toml): COALESCEの引数不足とNULLIFの第1引数NULL。
- [scalar/datetime.toml](cases/scalar/datetime.toml): ADD_MONTHSの小数月数。

上記13件と、NULL・符号・バインド・Unicode・メタデータなどの境界21件を合わせ、今回34ケースを追加した。
NULLIFの型検査全般とCOALESCEの混在型は見送り、ASTだけで確定できる不正引数を対象にした。
UPPERの数値変換は数値と分かる引数に限定し、階層SQLの既存UPPER構造を保持する。
非ASCII文字列の大文字化後の幅を入力の幅から推測する規則は追加していない。

[Oracle 18c REPLACE](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/REPLACE.html)、
[Oracle 18c TRANSLATE](https://docs.oracle.com/en/database/oracle/oracle-database/18/sqlrf/TRANSLATE.html)
の規則を参照し、結論はOracle 18c XEの実測で確認した。

## 前回のTDDと検証の経過

1. 修正前に13件を既存TOMLへ追加し、Oracle差分365件中その13件だけが失敗することを確認した。
2. 文字列のローカルテストは12件中7件失敗・3件エラーだった。値、SQL NULL、JDBC precisionを確認してから修正し、12件をGreenにした。
3. 共通SQL runtime、OracleCoercion、既存のメタデータ推論を再利用した。別のパーサや新しいテスト実行機構は追加していない。
4. UPPERの書き換えが階層SQL4件を拒否する回帰を検出した。数値と判明する引数だけを変換し、既存階層SQLを維持する形に修正した。
5. Oracle差分で境界ケースの値とメタデータを比較し、Maven verifyで全ローカルテスト・書式・runtimeパッケージ・JAR内容を検証した。

書式付きTO_CHARを4つ連結する追加探索では、値は一致したが、連結結果のJDBC precisionに19 / 2147483647の既存差分を発見した。
これは67件の一覧外で、書式関数の値の修正とは別の文字列幅の合成問題として未対応。
最終の書式テストは4つの結果を別々の列として取得し、それぞれの値とメタデータを厳密比較する。連結結果の互換性は主張しない。

再現SQL: SELECT TO_CHAR(-0.01, '999') || '/' || TO_CHAR(-0.01, '000') || '/' || TO_CHAR(-12, '999') || '/' || TO_CHAR(12, '999') AS value FROM dual

探索ログ: /tmp/orafit-review-oracle-red.log、/tmp/orafit-review-scalar-red.log、/tmp/orafit-review-oracle-green-1.log。

## 前回の検証結果（2026-09-06）

- ./mvnw verify: 547件成功、Failures 0、Errors 0、Skipped 0。書式、runtimeパッケージ生成、JAR内容検証も成功。
- ./.github/workflows/ci.sh: Oracle 18c XE 18.4.0.0.0対embedded PostgreSQL 17.10、386件成功、Failures 0、Errors 0、Skipped 0、終了コード0。
- 今回の製品コード差分: 6ファイル、追加115行・削除6行。テストと判断表はこの行数に含めない。
- 最終ログ: /tmp/orafit-review-verify-final.log、/tmp/orafit-review-oracle-final-2.log。
- 合計18件対応済み（前回5件＋今回13件）。49件は上表の理由で今回見送り。

## P1の実装と検証（2026-09-06）

既存のscalar/core.toml・number.toml・string.toml・datetime.tomlとquery/semantics.tomlに、P1の再現10件と境界ケースを追加した。handoverへの依存はない。

実装は既存のScalarLoweringRule、DatabaseTypeCoercionRule、ResultMetadataPlannerとextension/srcの数値・日時・文字列モジュールを拡張した。SQLの再解析、独立した型推論器、別のテストローダーは追加していない。NUMBERの除算は固定小数桁へのCASTではなく、整数商と剰余から丸める。

一時テーブルのCHAR比較で、列型解決がcurrent_schemaだけを参照していた問題を検出した。PostgreSQLのto_regclassで実際に見えるスキーマを取得し、既存の列メタデータ取得に渡す。型判定が必要な翻訳には、このカタログ問い合わせのコストが加わる。

### 対応範囲

- 元のP1の10ケースはすべてsameの厳密比較を維持する。既存の失敗ケースをrejectへ変更していない。
- NULLIF/COALESCEの数値・文字混在を型判定する。型未指定の直接バインド引数はFUNCTION_BIND_TYPEで拒否し、CAST(? AS NUMBER)など型を明示したSQLを受け付ける。この制限は新規のreject契約2件に記載した。
- CHARの長さ超過CASTは近似せず0A000で拒否する。文字セット・NLS全般やCHARのあらゆる暗黙変換をサポートする主張ではない。
- TO_DATEは既存の数値日付書式の範囲で入力末尾を検証する。月名、言語設定、無効日付のOracleエラー順全般は対象外。
- TIMESTAMP→DATEのCASTとDATE同士の減算を対象とする。Oracle DATEのDDL型マッピング全般やTIMESTAMPとの混在減算は別課題。
- NUMBERの他の演算・桁数未指定CASTの丸めはP2のまま。除算で得た結果以外のNUMBER全体の互換性は主張しない。

### TDDの記録

1. P1の再現10件を先に追加し、Oracle差分396件中その10件だけが失敗することを確認した（/tmp/orafit-p1-red.log）。
2. 値を修正してから境界ケースを追加し、翻訳を通らない列除算、CHARのBYTE長、型付きNULLのメタデータを検出して修正した。413件すべてのOracle差分が成功した（/tmp/orafit-p1-oracle-2.log）。
3. PreparedStatementのバインド順、NULL再設定、ゼロ除算後の再実行、物理DATE列、一時テーブルのCHAR比較をローカルテストへ追加。一時テーブルの比較でRedを確認し、列型取得を修正した（/tmp/orafit-p1-bind-red.log）。
4. DATE列・TIMESTAMPバインド・型付きNULL関数のOracleケースと、型未指定バインドの明示reject契約を追加し、最終検証を行った。

### P1の最終結果

- `./mvnw verify`: 585件、Failures 0、Errors 0、Skipped 0。AOSP書式、runtimeパッケージ、JAR内容検証を含め成功。
- `./.github/workflows/ci.sh`: Oracle 18c XE 18.4.0.0.0対embedded PostgreSQL 17.10、421件、Failures 0、Errors 0、Skipped 0、終了コード0。
- 今回追加したTOMLは35ケース（元の再現10件＋境界25件）。このうち33件がsame、2件が型未指定バインドの明示reject。別途、ローカル統合テスト3件を追加した。
- 最終ログ: `/tmp/orafit-p1-verify-final.log`、`/tmp/orafit-p1-oracle-final.log`。
- 全67件中、28件対応済み（前回18件＋P1の10件）。P2の19件、P3の20件は今回の実装対象に含めない。
