{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fmodern\fcharset0 Courier;}
{\colortbl;\red255\green255\blue255;\red153\green168\blue186;\red32\green32\blue32;}
{\*\expandedcolortbl;;\csgenericrgb\c60000\c65882\c72941;\csgenericrgb\c12549\c12549\c12549;}
\margl1440\margr1440\vieww11520\viewh8400\viewkind0
\pard\tx560\tx1120\tx1680\tx2240\tx2800\tx3360\tx3920\tx4480\tx5040\tx5600\tx6160\tx6720\pardirnatural\partightenfactor0

\f0\fs26 \cf2 \cb3 abstract class TransactionsListViewModel(\
    application: Application,\
    val accountId: String,\
    val configuration: AccountsAndTransactionsConfiguration,\
    val useCase: TransactionsUseCase,\
    val ioDispatcherWrapper: IoDispatcherWrapper,\
    val networkReader: NetworkReader,\
    val journeyDataRefreshTracker: JourneyDataRefreshTracker,\
) : AndroidViewModel(application) \{\
    private var viewErrorState: ViewErrorState? = null\
\
    private var networkRequestJob: Job? = null\
    private var minSearchLength: Int = 2\
    protected var lastUpdateTs: Long = 0L\
\
    @ExperimentalCoroutinesApi\
    private val transactionsViewState = Channel<TransactionsState>(Channel.RENDEZVOUS)\
\
    @VisibleForTesting\
    val sectionedCombinedOrCompletedTransactionList = mutableListOf<Any>()\
\
    @VisibleForTesting\
    val combinedOrCompletedTransactionList = mutableListOf<TransactionItem>()\
    private var pageNumberForCombinedOrCompletedTransactions = 0\
    private var isLastPageForCombinedOrCompletedTransactions = false\
\
    private val _firstPageEmpty = MutableStateFlow<Boolean?>(null)\
    val firstPageEmpty: StateFlow<Boolean?> get() = _firstPageEmpty\
\
    private val _firstPageCreditCardEmpty = MutableStateFlow<Boolean?>(null)\
    val firstPageCreditCardEmpty: StateFlow<Boolean?> get() = _firstPageCreditCardEmpty\
\
    var transactionSearchModel: TransactionSearch? =\
        TransactionSearch(\
            period = TransactionSearchPeriod.CURRENT,\
            status = TransactionSearchStatus.BOTH,\
        )\
\
    var lastTwoPeriodsTransactionSearchModel: TransactionSearch? =\
        TransactionSearch(\
            period = TransactionSearchPeriod.LAST_TWO_STATEMENT_CLOSED,\
            status = TransactionSearchStatus.BOTH,\
        )\
\
    @VisibleForTesting\
    val pendingTransactionList = mutableListOf<TransactionItem>()\
    private var pageNumberForPendingTransactions = 0\
\
    @VisibleForTesting\
    var isPendingTransactionsSectionExpanded = false\
\
    @VisibleForTesting\
    var isLastPageForPendingTransactions = false\
\
    private val transactionsConfiguration = configuration.transactions\
\
    private val accountUsageRepresentationProductResolver by lazy \{\
        createAccountUsageRepresentationProductResolver(\
            configuration,\
            configuration.transactions.accountUsageRepresentationUiDataMapper,\
        )\
    \}\
\
    private fun getCopyOfSectionedCombinedOrCompletedTransactionsList() =\
        List(sectionedCombinedOrCompletedTransactionList.size) \{\
            sectionedCombinedOrCompletedTransactionList[it]\
        \}\
\
    private fun hasCachedData() =\
        sectionedCombinedOrCompletedTransactionList.isNotEmpty() ||\
                pendingTransactionList.isNotEmpty()\
\
    @MainThread\
    fun updateTs() \{\
        lastUpdateTs = System.currentTimeMillis()\
    \}\
\
    @OptIn(ExperimentalCoroutinesApi::class)\
    fun subscribe() =\
        transactionsViewState\
            .receiveAsFlow()\
            .map \{\
                saveViewErrorState(it)\
                it\
            \}\
\
    fun obtainAccountUsageRepresentation(product: ParcelableProduct) = accountUsageRepresentationProductResolver.resolve(product)\
\
    private fun saveViewErrorState(state: TransactionsState) \{\
        when (state) \{\
            is TransactionsState.SuccessFirstPagePendingOnTopTrue -> \{\
                viewErrorState =\
                    when \{\
                        state.completed == null -> \{\
                            ViewErrorState(\
                                ViewErrorState.TransactionItemStatus.COMPLETED,\
                                null,\
                            )\
                        \}\
\
                        state.pending == null -> \{\
                            ViewErrorState(\
                                ViewErrorState.TransactionItemStatus.PENDING,\
                                null,\
                            )\
                        \}\
\
                        else -> \{\
                            null\
                        \}\
                    \}\
            \}\
\
            is TransactionsState.ErrorFirstPage ->\
                if (state.showFullScreen) \{\
                    TrackingHelper.trackASMFP(\
                        TAG_FP,\
                        "$\{state.errorResponse\}",\
                        "$\{TrackingHelper.getStackTrace()\}",\
                    )\
                    viewErrorState =\
                        ViewErrorState(null, state.reason)\
                \}\
\
            is TransactionsState.ErrorPageCombinedOrCompleted -> Unit\
            is TransactionsState.ErrorPagePending -> Unit\
            is TransactionsState.SuccessPageCombinedOrCompleted ->\
                if (\
                    !transactionsConfiguration.showPendingTransactionsOnTop\
                ) \{\
                    viewErrorState =\
                        null\
                \}\
\
            is TransactionsState.SuccessPagePending -> Unit\
            is TransactionsState.LoadingAll -> Unit\
            is TransactionsState.LoadingPagePending -> Unit\
            is TransactionsState.LoadingPageCombinedOrCompleted -> Unit\
            is TransactionsState.SameState -> Unit\
            is TransactionsState.NoSearchQuery -> Unit\
            is SecondPeriodState.SuccessPagePending -> Unit\
            SecondPeriodState -> Unit\
        \}\
    \}\
\
    fun onSectionErrorViewClosed() \{\
        viewErrorState = null\
        loadCachedTransactions()\
    \}\
\
    @VisibleForTesting\
    @OptIn(ExperimentalCoroutinesApi::class)\
    fun executeIfConnectedOrPushErrorState(\
        orErrorState: TransactionsState,\
        action: () -> Unit,\
    ) \{\
        if (!networkReader.isNetworkConnected()) \{\
            viewModelScope.launch(ioDispatcherWrapper.dispatcher) \{\
                transactionsViewState.send(orErrorState)\
            \}\
            return\
        \}\
        action.invoke()\
    \}\
\
    private var lastQuerySearched: String? = ""\
\
    fun searchTransactions(\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        if (\
            lastQuerySearched != query ||\
            transactionSearchModel?.amountRange != amountRange ||\
            transactionSearchModel?.types != types\
        ) \{\
            sectionedCombinedOrCompletedTransactionList.clear()\
            combinedOrCompletedTransactionList.clear()\
            pendingTransactionList.clear()\
        \}\
        lastQuerySearched = query\
        if ((query?.length ?: 0) >= minSearchLength) \{\
            loadTransactions(\
                action = Action.OnSearchQueryChanged,\
                query = query,\
                amountRange = amountRange,\
                types = types,\
            )\
            return\
        \}\
        emitNoSearchQueryState()\
    \}\
\
    fun loadTransactions(\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        when (action) \{\
            Action.OnFirstLoad ->\
                onFirstLoad(\
                    action = action,\
                    query = query,\
                    amountRange = amountRange,\
                    types = types,\
                )\
\
            Action.OnSearchQueryChanged ->\
                onSearchQueryChanged(\
                    action = action,\
                    query = query,\
                    amountRange = amountRange,\
                    types = types,\
                )\
\
            Action.OnRefreshWithContent ->\
                fetchFirstPageFromRemote(\
                    showFullScreen = false,\
                    action = action,\
                    query = query,\
                    amountRange = amountRange,\
                    types = types,\
                )\
\
            Action.OnRefreshWithoutContent,\
            Action.OnFullScreenTryAgain,\
            ->\
                fetchFirstPageFromRemote(\
                    showFullScreen = true,\
                    action = action,\
                    query = query,\
                    amountRange = amountRange,\
                    types = types,\
                )\
\
            Action.OnNextPageCombinedOrCompletedTransactions ->\
                executeIfConnectedOrPushErrorState(\
                    orErrorState =\
                    TransactionsState.ErrorPageCombinedOrCompleted(\
                        getCopyOfSectionedCombinedOrCompletedTransactionsList(),\
                        ErrorReason.ReasonNoInternet,\
                        getFooterListState(false),\
                    ),\
                ) \{\
                    loadRemoteTransactions(\
                        action = action,\
                        query = query,\
                    )\
                \}\
\
            Action.OnNextPagePendingTransactions -> \{\
                if (!isPendingTransactionsSectionExpanded &&\
                    pageNumberForPendingTransactions ==\
                    1 &&\
                    pendingTransactionList.size > MAX_PENDING_TRANSACTIONS_COLLAPSED\
                ) \{\
                    isPendingTransactionsSectionExpanded = true\
                    loadPendingTransactionsListOnScreen()\
                \} else \{\
                    // app needs to fetch pending transactions from remote server\
                    executeIfConnectedOrPushErrorState(\
                        orErrorState =\
                        TransactionsState.ErrorPagePending(\
                            buildAdapterListForPendingTransactions(\
                                pendingTransactionList,\
                                FooterState.Button,\
                            ),\
                            ErrorReason.ReasonNoInternet,\
                        ),\
                    ) \{ loadRemoteTransactions(action, query) \}\
                \}\
            \}\
        \}\
    \}\
\
    // Load the pending transactionsList data on UI\
    @OptIn(ExperimentalCoroutinesApi::class)\
    private fun loadPendingTransactionsListOnScreen() \{\
        viewModelScope.launch(ioDispatcherWrapper.dispatcher) \{\
            transactionsViewState.send(\
                TransactionsState.SuccessPagePending(\
                    buildAdapterListForPendingTransactions(\
                        pendingTransactionList,\
                        if (isLastPageForPendingTransactions) \{\
                            FooterState.None\
                        \} else \{\
                            FooterState.Button\
                        \},\
                    ),\
                ),\
            )\
        \}\
    \}\
\
    private fun onFirstLoad(\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        val actionIfLastUpdated =\
            if (lastUpdateTs <\
                journeyDataRefreshTracker.lastJourneyRestartTs\
            ) \{\
                Action.OnRefreshWithContent\
            \} else \{\
                action\
            \}\
        obtainCachedDataIfAvailable \{\
            fetchFirstPageFromRemote(\
                showFullScreen = true,\
                action = actionIfLastUpdated,\
                query = query,\
                amountRange = amountRange,\
                types = types,\
            )\
        \}\
    \}\
\
    private fun onSearchQueryChanged(\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        if (query.isNullOrEmpty()) \{\
            emitNoSearchQueryState()\
            return\
        \}\
        obtainCachedDataIfAvailable \{\
            fetchFirstPageFromRemote(\
                showFullScreen = true,\
                action = action,\
                query = query,\
                amountRange = amountRange,\
                types = types,\
            )\
        \}\
    \}\
\
    // on First load or Search get the CachedData if exist otherwise get the data from remote\
    private fun obtainCachedDataIfAvailable(fetchFromRemote: () -> Unit) \{\
        if (viewErrorState != null || hasCachedData()) \{\
            loadCachedTransactions()\
            return\
        \}\
        fetchFromRemote()\
    \}\
\
    private fun fetchFirstPageFromRemote(\
        showFullScreen: Boolean,\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        executeIfConnectedOrPushErrorState(\
            orErrorState =\
            TransactionsState.ErrorFirstPage(\
                showFullScreen = showFullScreen,\
                reason = ErrorReason.ReasonNoInternet,\
            ),\
        ) \{\
            pageNumberForCombinedOrCompletedTransactions = 0\
            sectionedCombinedOrCompletedTransactionList.clear()\
            combinedOrCompletedTransactionList.clear()\
            isPendingTransactionsSectionExpanded = false\
            pageNumberForPendingTransactions = 0\
            pendingTransactionList.clear()\
            loadRemoteTransactions(action, query, amountRange, types)\
        \}\
    \}\
\
    private fun buildAdapterListForPendingTransactions(\
        transactionsList: List<TransactionItem>,\
        state: FooterState,\
    ) = if (transactionsList.isNotEmpty()) \{\
        transactionsList + PendingTransactionsFooterItem(state)\
    \} else \{\
        emptyList()\
    \}\
\
    private fun getFooterListState(isLoading: Boolean = true): FooterListState =\
        if (!isLoading) \{\
            FooterListState.NotVisible\
        \} else if (isLastPageForCombinedOrCompletedTransactions) \{\
            FooterListState.EndOfTheList\
        \} else \{\
            FooterListState.Loading\
        \}\
\
    @VisibleForTesting\
    @OptIn(ExperimentalCoroutinesApi::class)\
    fun loadCachedTransactions() \{\
        fun getSuccessCombinedOrCompletedState() =\
            TransactionsState.SuccessPageCombinedOrCompleted(\
                getCopyOfSectionedCombinedOrCompletedTransactionsList(),\
                getFooterListState(),\
            )\
\
        fun getSuccessPagePendingState() =\
            TransactionsState.SuccessPagePending(\
                buildAdapterListForPendingTransactions(\
                    if (isPendingTransactionsSectionExpanded) \{\
                        pendingTransactionList\
                    \} else \{\
                        pendingTransactionList.take(MAX_PENDING_TRANSACTIONS_COLLAPSED)\
                    \},\
                    getPendingTransactionsFooterState(),\
                ),\
            )\
\
        viewModelScope.launch(ioDispatcherWrapper.dispatcher) \{\
            transactionsViewState.send(\
                TransactionsState.LoadingAll,\
            )\
\
            val fullScreenError = viewErrorState?.errorFullScreen\
            fullScreenError?.let \{\
                transactionsViewState.send(\
                    TransactionsState.ErrorFirstPage(\
                        showFullScreen = true,\
                        reason = fullScreenError,\
                    ),\
                )\
                return@let\
            \}\
            if (transactionsConfiguration.showPendingTransactionsOnTop) \{\
                val transactionsState =\
                    when (viewErrorState?.errorPartialScreen) \{\
                        ViewErrorState.TransactionItemStatus.COMPLETED ->\
                            TransactionsState.SuccessFirstPagePendingOnTopTrue(\
                                null,\
                                getSuccessPagePendingState(),\
                            )\
\
                        ViewErrorState.TransactionItemStatus.PENDING ->\
                            TransactionsState.SuccessFirstPagePendingOnTopTrue(\
                                getSuccessCombinedOrCompletedState(),\
                                null,\
                            )\
\
                        null ->\
                            TransactionsState.SuccessFirstPagePendingOnTopTrue(\
                                getSuccessCombinedOrCompletedState(),\
                                getSuccessPagePendingState(),\
                            )\
                    \}\
\
                transactionsViewState.send(transactionsState)\
                return@launch\
            \}\
            transactionsViewState.send(getSuccessCombinedOrCompletedState())\
        \}\
    \}\
\
    @OptIn(ExperimentalCoroutinesApi::class)\
    private fun emitNoSearchQueryState() \{\
        viewModelScope.launch \{\
            transactionsViewState.send(TransactionsState.NoSearchQuery)\
        \}\
    \}\
\
    private fun loadRemoteTransactions(\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        if (networkRequestJob?.isActive == true) \{\
            networkRequestJob?.cancel()\
        \}\
        if (transactionsConfiguration.showPendingTransactionsOnTop) \{\
            loadRemoteTransactionsBySections(action, query, amountRange, types)\
        \} else \{\
            loadRemoteTransactionsCombinedSections(action, query, amountRange, types)\
        \}\
    \}\
\
    private fun getLoadingState(action: Action) =\
        when (action) \{\
            Action.OnFirstLoad,\
            Action.OnSearchQueryChanged,\
            Action.OnFullScreenTryAgain,\
            -> TransactionsState.LoadingAll\
\
            Action.OnRefreshWithContent,\
            Action.OnRefreshWithoutContent,\
            -> TransactionsState.SameState\
\
            Action.OnNextPagePendingTransactions ->\
                TransactionsState.LoadingPagePending(\
                    buildAdapterListForPendingTransactions(\
                        pendingTransactionList,\
                        FooterState.Loading,\
                    ),\
                )\
\
            Action.OnNextPageCombinedOrCompletedTransactions ->\
                TransactionsState.LoadingPageCombinedOrCompleted(\
                    getFooterListState(),\
                )\
        \}\
\
    @OptIn(ExperimentalCoroutinesApi::class)\
    private fun loadRemoteTransactionsCombinedSections(\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        networkRequestJob =\
            viewModelScope.launch(ioDispatcherWrapper.dispatcher) \{\
                transactionsViewState.send(getLoadingState(action))\
                val response =\
                    useCase.getTransactions(\
                        params =\
                        TransactionGetRequestParameters \{\
                            this.from = pageNumberForCombinedOrCompletedTransactions\
                            this.size = transactionsConfiguration.pageSize\
                            this.arrangementId = accountId\
                            this.query = query\
                            this.state = null\
                            this.amountGreaterThan = amountRange?.amountGreaterThan\
                            this.amountLessThan = amountRange?.amountLessThan\
                            this.types = types?.map \{ it.value \}\
                        \},\
                    )\
\
                transactionsViewState.send(\
                    getSectionStateForCompletedOrCombinedTransactions(\
                        action,\
                        response,\
                    ),\
                )\
            \}\
    \}\
\
    @OptIn(ExperimentalCoroutinesApi::class)\
    fun loadLastTwoPeriodsTransactions(\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        networkRequestJob =\
            viewModelScope.launch(ioDispatcherWrapper.dispatcher) \{\
                transactionsViewState.send(getLoadingState(action))\
                val response =\
                    useCase.getTransactions(\
                        params =\
                        TransactionGetRequestParameters \{\
                            this.from = pageNumberForCombinedOrCompletedTransactions\
                            this.size = TRANSACTION_PAGE_SIZE\
                            this.arrangementId = accountId\
                            this.query = query\
                            this.state = null\
                            this.amountGreaterThan = amountRange?.amountGreaterThan\
                            this.amountLessThan = amountRange?.amountLessThan\
                            this.types = types?.map \{ it.value \}\
                        \}\
                    )\
\
                transactionsViewState.send(\
                    getLastTwoPeriods(\
                        response\
                    ),\
                )\
            \}\
    \}\
\
    @OptIn(ExperimentalCoroutinesApi::class)\
    private fun loadRemoteTransactionsBySections(\
        action: Action,\
        query: String? = null,\
        amountRange: TransactionSearchAmountRange? = null,\
        types: List<TransactionSearchType>? = null,\
    ) \{\
        fun CoroutineScope.getCompletedTransactionsAsync() =\
            async \{\
                useCase.getTransactions(\
                    params =\
                    TransactionGetRequestParameters \{\
                        this.from = pageNumberForCombinedOrCompletedTransactions\
                        this.size = transactionsConfiguration.pageSize\
                        this.arrangementId = accountId\
                        this.query = query\
                        this.state = State.COMPLETED\
                        this.amountGreaterThan = amountRange?.amountGreaterThan\
                        this.amountLessThan = amountRange?.amountLessThan\
                        this.types = types?.map \{ it.value \}\
                    \},\
                )\
            \}\
\
        fun CoroutineScope.getPendingTransactionsAsync() =\
            async \{\
                useCase.getTransactions(\
                    params =\
                    TransactionGetRequestParameters \{\
                        this.from = pageNumberForPendingTransactions\
                        this.size = transactionsConfiguration.pageSize\
                        this.arrangementId = accountId\
                        this.query = query\
                        this.state = State.UNCOMPLETED\
                        this.amountGreaterThan = amountRange?.amountGreaterThan\
                        this.amountLessThan = amountRange?.amountLessThan\
                        this.types = types?.map \{ it.value \}\
                    \},\
                )\
            \}\
\
        networkRequestJob =\
            viewModelScope.launch(ioDispatcherWrapper.dispatcher) \{\
                transactionsViewState.send(getLoadingState(action))\
\
                when (action) \{\
                    Action.OnFirstLoad,\
                    Action.OnSearchQueryChanged,\
                    Action.OnFullScreenTryAgain,\
                    Action.OnRefreshWithContent,\
                    Action.OnRefreshWithoutContent,\
                    -> \{\
                        val responses =\
                            listOf(getCompletedTransactionsAsync(), getPendingTransactionsAsync())\
                                .awaitAll()\
\
                        val completedSectionState =\
                            getSectionStateForCompletedOrCombinedTransactions(\
                                action,\
                                responses.first(),\
                            )\
                        val pendingSectionState =\
                            getSectionStateForPendingTransactions(action, responses.last())\
\
                        val state: TransactionsState =\
                            if (completedSectionState !is\
                                        TransactionsState.SuccessPageCombinedOrCompleted &&\
                                pendingSectionState !is TransactionsState.SuccessPagePending\
                            ) \{\
                                // If both API calls failed\
                                TransactionsState.ErrorFirstPage(\
                                    action != Action.OnRefreshWithContent,\
                                    ErrorReason.ReasonGenericFailure,\
                                )\
                            \} else \{\
                                TransactionsState.SuccessFirstPagePendingOnTopTrue(\
                                    completed =\
                                    if (completedSectionState is\
                                                TransactionsState.SuccessPageCombinedOrCompleted\
                                    ) \{\
                                        completedSectionState\
                                    \} else \{\
                                        null\
                                    \},\
                                    pending =\
                                    if (\
                                        pendingSectionState is TransactionsState.SuccessPagePending\
                                    ) \{\
                                        pendingSectionState\
                                    \} else \{\
                                        null\
                                    \},\
                                )\
                            \}\
\
                        transactionsViewState.send(state)\
                    \}\
\
                    Action.OnNextPagePendingTransactions ->\
                        transactionsViewState.send(\
                            getSectionStateForPendingTransactions(\
                                action,\
                                getPendingTransactionsAsync().await(),\
                            ),\
                        )\
\
                    Action.OnNextPageCombinedOrCompletedTransactions ->\
                        transactionsViewState.send(\
                            getSectionStateForCompletedOrCombinedTransactions(\
                                action,\
                                getCompletedTransactionsAsync().await(),\
                            ),\
                        )\
                \}\
            \}\
    \}\
\
    private fun getSectionStateForPendingTransactions(\
        action: Action,\
        callState: CallState<out List<TransactionItem>>,\
    ): TransactionsState =\
        when (callState) \{\
            is Success -> \{\
                val pagedTransactions = callState.data\
                pendingTransactionList.addAll(pagedTransactions)\
\
                isLastPageForPendingTransactions =\
                    pagedTransactions.size != transactionsConfiguration.pageSize\
\
                val state =\
                    TransactionsState.SuccessPagePending(\
                        buildAdapterListForPendingTransactions(\
                            if (pageNumberForPendingTransactions == 0 &&\
                                pagedTransactions.size > MAX_PENDING_TRANSACTIONS_COLLAPSED\
                            ) \{\
                                pendingTransactionList.take(MAX_PENDING_TRANSACTIONS_COLLAPSED)\
                            \} else \{\
                                pendingTransactionList\
                            \},\
                            getPendingTransactionsFooterState(),\
                        ),\
                    )\
\
                pageNumberForPendingTransactions++\
\
                state\
            \}\
            // only called when page == 0\
            is Empty -> TransactionsState.SuccessPagePending(listOf())\
            is Error -> \{\
                val errorCode = callState.errorResponse.responseCode.toString()\
                val errorResponse = callState.errorResponse.errorMessage\
                when (action) \{\
                    Action.OnRefreshWithContent ->\
                        TransactionsState.ErrorFirstPage(\
                            false,\
                            ErrorReason.ReasonGenericFailure,\
                            errorCode,\
                            errorResponse,\
                        )\
\
                    Action.OnFirstLoad,\
                    Action.OnSearchQueryChanged,\
                    Action.OnFullScreenTryAgain,\
                    Action.OnRefreshWithoutContent,\
                    ->\
                        TransactionsState.ErrorFirstPage(\
                            true,\
                            ErrorReason.ReasonGenericFailure,\
                            errorCode,\
                            errorResponse,\
                        )\
\
                    Action.OnNextPagePendingTransactions ->\
                        TransactionsState.ErrorPagePending(\
                            buildAdapterListForPendingTransactions(\
                                pendingTransactionList,\
                                FooterState.Button,\
                            ),\
                            ErrorReason.ReasonGenericFailure,\
                        )\
\
                    Action.OnNextPageCombinedOrCompletedTransactions ->\
                        throw IllegalStateException("$action should never be the case here")\
                \}\
            \}\
        \}\
\
    @VisibleForTesting\
    fun getPendingTransactionsFooterState(): FooterState =\
        if (isPendingTransactionsSectionExpanded) \{\
            if (isLastPageForPendingTransactions) \{\
                FooterState.None\
            \} else \{\
                FooterState.Button\
            \}\
        \} else \{\
            if (pendingTransactionList.size > MAX_PENDING_TRANSACTIONS_COLLAPSED ||\
                !isLastPageForPendingTransactions\
            ) \{\
                FooterState.Button\
            \} else \{\
                FooterState.None\
            \}\
        \}\
\
    fun updateIsFirstPageEmpty(isEmpty: Boolean) \{\
        if (_firstPageEmpty.value == null) \{\
            _firstPageEmpty.value = isEmpty\
        \}\
    \}\
\
    fun updateIsFirstPageCreditCardEmpty(isEmpty: Boolean) \{\
        _firstPageCreditCardEmpty.value = isEmpty\
    \}\
\
    private fun getLastTwoPeriods(callState: CallState<out List<TransactionItem>>): TransactionsState =\
        when (callState) \{\
            is Success -> \{\
                SecondPeriodState.SuccessPagePending(callState.data)\
            \}\
            is Empty -> \{\
                SecondPeriodState.SuccessPagePending(emptyList())\
            \}\
            is Error -> \{\
                TransactionsState.ErrorFirstPage(\
                    showFullScreen = false,\
                    errorResponse = callState.errorResponse.errorMessage,\
                    errorCode = callState.errorResponse.responseCode.toString(),\
                    reason = ErrorReason.ReasonGenericFailure\
                )\
            \}\
        \}\
\
    @VisibleForTesting\
    fun getSectionStateForCompletedOrCombinedTransactions(\
        action: Action,\
        callState: CallState<out List<TransactionItem>>,\
    ): TransactionsState =\
        when (callState) \{\
            is Success -> \{\
                val pagedTransactions = callState.data\
                isLastPageForCombinedOrCompletedTransactions =\
                    pagedTransactions.size != transactionsConfiguration.pageSize\
\
                // update page number if the current list is not from last page.\
                if (!isLastPageForCombinedOrCompletedTransactions) \{\
                    pageNumberForCombinedOrCompletedTransactions++\
                \}\
\
                sectionedCombinedOrCompletedTransactionList.addAll(\
                    transformList(\
                        pagedTransactions,\
                        combinedOrCompletedTransactionList.lastElement?.bookingDate,\
                    ),\
                )\
                combinedOrCompletedTransactionList.addAll(pagedTransactions)\
\
                TransactionsState.SuccessPageCombinedOrCompleted(\
                    getCopyOfSectionedCombinedOrCompletedTransactionsList(),\
                    getFooterListState(isLoading = isLastPageForCombinedOrCompletedTransactions),\
                )\
            \}\
            // only called if page = 0\
            is Empty ->\
                TransactionsState.SuccessPageCombinedOrCompleted(\
                    listOf(),\
                    getFooterListState(false),\
                )\
\
            is Error -> \{\
                val errorCode = callState.errorResponse.responseCode.toString()\
                val errorResponse = callState.errorResponse.errorMessage\
                when (action) \{\
                    Action.OnRefreshWithContent ->\
                        TransactionsState.ErrorFirstPage(\
                            false,\
                            ErrorReason.ReasonGenericFailure,\
                            errorCode,\
                            errorResponse,\
                        )\
\
                    Action.OnFirstLoad,\
                    Action.OnSearchQueryChanged,\
                    Action.OnFullScreenTryAgain,\
                    Action.OnRefreshWithoutContent,\
                    ->\
                        TransactionsState.ErrorFirstPage(\
                            true,\
                            ErrorReason.ReasonGenericFailure,\
                            errorCode,\
                            errorResponse,\
                        )\
\
                    Action.OnNextPagePendingTransactions -> throw IllegalStateException(\
                        "$action should never be the case here",\
                    )\
\
                    Action.OnNextPageCombinedOrCompletedTransactions ->\
                        TransactionsState.ErrorPageCombinedOrCompleted(\
                            getCopyOfSectionedCombinedOrCompletedTransactionsList(),\
                            ErrorReason.ReasonGenericFailure,\
                            getFooterListState(false),\
                        )\
                \}\
            \}\
        \}\
\
    fun showNoTransactionCombined(\
        listItems: List<Any>,\
        startWithSearch: Boolean,\
    ): Boolean = listItems.isEmpty() && !startWithSearch\
\
    fun showNoTransactionPendingTop(\
        state: TransactionsState.SuccessFirstPagePendingOnTopTrue,\
        startWithSearch: Boolean,\
    ): Boolean =\
        (state.completed != null && state.completed.listItems.isNotEmpty()) ||\
                (state.pending != null && state.pending.listItems.isNotEmpty()) ||\
                startWithSearch\
\
    fun showNoSearchPendingTop(\
        state: TransactionsState.SuccessFirstPagePendingOnTopTrue,\
        startWithSearch: Boolean,\
    ): Boolean =\
        startWithSearch &&\
                state.pending != null &&\
                state.completed != null &&\
                state.pending.listItems.isEmpty() &&\
                state.completed.listItems.isEmpty()\
\
    private fun transformList(\
        transactions: List<TransactionItem>,\
        previousBookingDate: LocalDate? = null,\
    ): List<Any> \{\
        val result = mutableListOf<Any>()\
\
        val dateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)\
\
        val dateToday = LocalDate.now()\
        val dateYesterday = dateToday.minusDays(1)\
\
        var bookingDate = previousBookingDate\
\
        transactions.map \{ item ->\
            if (bookingDate != item.bookingDate) \{\
                bookingDate = item.bookingDate\
\
                bookingDate?.let \{ date ->\
                    val sectionHeader =\
                        when (date) \{\
                            dateToday ->\
                                DeferredText.Resource(\
                                    R.string.accountsAndTransactions_transactions_labels_today,\
                                )\
\
                            dateYesterday ->\
                                DeferredText.Resource(\
                                    R.string.accountsAndTransactions_transactions_labels_yesterday,\
                                )\
\
                            else -> DeferredText.Constant(date.format(dateTimeFormatter))\
                        \}\
\
                    result.add(\
                        ListSectionHeaderItem(\
                            sectionHeader,\
                            configuration.accountsScreen.headerTextStyle,\
                            configuration.accountsScreen.headerTextColor,\
                            configuration.accountsScreen.headerTextAllCaps,\
                        ),\
                    )\
                \}\
            \}\
\
            result.add(item)\
        \}\
\
        return result\
    \}\
\
    private val <T> List<T>.lastElement: T?\
        get() = if (!isEmpty()) get(size - 1) else null\
\
    @OptIn(ExperimentalCoroutinesApi::class)\
    override fun onCleared() \{\
        networkRequestJob?.cancel()\
        super.onCleared()\
    \}\
\
    internal companion object \{\
        private const val TAG_FP = "TLVM_FP"\
        private const val TRANSACTION_PAGE_SIZE = 1\
\
        @VisibleForTesting\
        const val MAX_PENDING_TRANSACTIONS_COLLAPSED = 2\
    \}\
\}\
\
/**\
 * @param errorPartialScreen null if there are no partial error in the view.\
 * @param errorFullScreen non-null if there is a full screen error in the view.\
 */\
internal data class ViewErrorState(\
    val errorPartialScreen: TransactionItemStatus?,\
    val errorFullScreen: ErrorReason?,\
) \{\
    enum class TransactionItemStatus \{\
        PENDING,\
        COMPLETED\
    \}\
\}\
\
\
}