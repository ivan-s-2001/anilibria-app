# Проверка исходников TV-клиентов — 22.10.2026

Основа продукта: [полная спецификация](UX-SPECIFICATION.md). Этот файл фиксирует непосредственно прочитанные компоненты; он не означает полного запуска или тестирования чужих приложений. Код ниже реализован самостоятельно на Android Views; Compose-код проектов не копировался.

| Проект и зафиксированная версия | Прочитанный компонент | Наблюдение | Применение / состояние |
|---|---|---|---|
| Nuvio `1e99d4e5d20535f48234492278c503101f0eb1d4` | HomeScreenFocusState.kt | Ключ строки, ID карточки, вертикальное смещение и ключ горизонтального якоря хранятся раздельно; индекс помечен устаревшим | Вложенные полки сохраняют ID карточки и якорь; восстановление после layout |
| Nuvio, та же версия | FocusRestoreUtils.kt | Запрос фокуса откладывается на кадры и повторяется ограниченно | Фокус назначается после layout и появления дочерней карточки |
| Nuvio, та же версия | SidebarNavigation.kt | selectedRoute, isFocused и isExpanded независимы; onNavigate вызывается из onClick | Боковое меню; стрелки не открывают разделы; активный раздел красный, фокус белый |
| Nuvio, та же версия | EpisodesSidePanel.kt, EpisodesListView | Выбор сначала по последней открытой серии, затем текущей; загрузка не вытягивает фокус с вкладок сезонов | Требует внедрения в новый боковой интерфейс плеера; существующий диалог ещё не соответствует |
| Wholphin `551501fa759d95f7eb601455149ccdf2e8602192` | NavigationDrawerAndroid.kt | Размеры 64/224dp; раскрытие по фокусу; отдельные selected/focused цвета; scale 1 | Компактная боковая навигация; наши размеры адаптированы, анимация пока не внедрена |
| Wholphin, та же версия | FocusableItemRow.kt | Заглушка фокусируема, чтобы область можно было прокрутить | Ошибки должны иметь повтор; статические заголовки не фокусируемы |
| Wholphin, та же версия | SeriesOverviewContent.kt | Выбранная вкладка сохранена; смена сезона выставляет requestFocusAfterSeason; запрос только после EpisodeList.Success; у ряда собственный focusRestorer | Раздельные вкладки и контент; сохранение выбранной карточки; полная модель эпизодов ещё предстоит |
| Jellyfin Android TV `56c6fc44dc4bd1898ab25af030df575cd25f4618` | NextUpViewModel.kt | Явные INITIALIZED/NO_DATA/PLAY_NEXT/CLOSE; отсутствие следующего элемента не равно ошибке | Следующая серия должна иметь явную отмену; пока не реализовано |
| JetStream `15ac8df7df91a07450751a5fe8c6fd17a6a4297f` | HomeScreen.kt | LazyColumn с самостоятельными полками; фиксированная высота витрины; отдельные Loading/Error/Ready | Новые эпизоды и расписание становятся горизонтальными полками; размеры заданы до загрузки изображения |

## Точные ссылки

- [Nuvio: состояние главной](https://github.com/NuvioMedia/NuvioTV/blob/1e99d4e5d20535f48234492278c503101f0eb1d4/app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreenFocusState.kt)
- [Nuvio: восстановление](https://github.com/NuvioMedia/NuvioTV/blob/1e99d4e5d20535f48234492278c503101f0eb1d4/app/src/main/java/com/nuvio/tv/ui/screens/detail/FocusRestoreUtils.kt)
- [Nuvio: меню](https://github.com/NuvioMedia/NuvioTV/blob/1e99d4e5d20535f48234492278c503101f0eb1d4/app/src/main/java/com/nuvio/tv/ui/components/SidebarNavigation.kt)
- [Nuvio: серии в плеере](https://github.com/NuvioMedia/NuvioTV/blob/1e99d4e5d20535f48234492278c503101f0eb1d4/app/src/main/java/com/nuvio/tv/ui/screens/player/EpisodesSidePanel.kt)
- [Wholphin: меню](https://github.com/damontecres/Wholphin/blob/551501fa759d95f7eb601455149ccdf2e8602192/app/src/main/java/com/github/damontecres/wholphin/ui/nav/NavigationDrawerAndroid.kt)
- [Wholphin: заглушка](https://github.com/damontecres/Wholphin/blob/551501fa759d95f7eb601455149ccdf2e8602192/app/src/main/java/com/github/damontecres/wholphin/ui/components/FocusableItemRow.kt)
- [Wholphin: эпизоды](https://github.com/damontecres/Wholphin/blob/551501fa759d95f7eb601455149ccdf2e8602192/app/src/main/java/com/github/damontecres/wholphin/ui/detail/series/SeriesOverviewContent.kt)
- [Jellyfin: Next Up](https://github.com/jellyfin/jellyfin-androidtv/blob/56c6fc44dc4bd1898ab25af030df575cd25f4618/app/src/main/java/org/jellyfin/androidtv/ui/playback/nextup/NextUpViewModel.kt)
- [JetStream: главная](https://github.com/android/tv-samples/blob/15ac8df7df91a07450751a5fe8c6fd17a6a4297f/JetStreamCompose/jetstream/src/main/java/com/google/jetstream/presentation/screens/home/HomeScreen.kt)

## Остальной объём спецификации

Весь документ принят как целевая модель, а не объявлен реализованным. Последующие обязательные части: полноценное Моё/история/очередь; продолжение с Next Up; отдельное расписание; фильтры с черновиком; OTP после проверки сервера; переключение вида и поиск эпизодов; боковые панели плеера; настройки; реальная проверка на пульте. Внешние API остаются необязательным обогащением.
